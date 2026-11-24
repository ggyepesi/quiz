package domain.config;

import domain.datasource.Datasources;
import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.api.SourceBindingTarget;
import domain.datasource.api.SourceRecipe;
import domain.datasource.schema.FieldType;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forces the first real construct projection used by #557's field editor. */
class FieldConfigurationProjectionTest {

    @Test void deityGroupMembersShowsRoutesAndBothWikipediaEvidenceRecipes()
            throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/egyptiangods/egyptiangods.model.json"));
        GeneratedClassModel owner = project.findClass("DeityGroup");
        GeneratedFieldModel field = owner.fields().stream()
                .filter(value -> value.name().equals("members")).findFirst().orElseThrow();

        ConfigurationNode projection = FieldConfigurationProjection.project(
                project, owner, field, Datasources.standard());
        List<ConfigurationNode> nodes = flatten(projection);
        List<String> captions = nodes.stream().map(ConfigurationNode::caption).toList();

        assertTrue(captions.stream().anyMatch(value -> value.startsWith(
                "Primary: Wikidata")));
        assertTrue(captions.contains("Route: Listed members"));
        assertTrue(captions.contains("Step 1: outgoing P527"));
        assertTrue(captions.contains("Article table"));
        assertTrue(captions.contains("Section: Description > List of names, "
                + "provenances and tasks (Wilkinson et al)"));
        assertTrue(captions.contains("Field mapping"));
        assertTrue(captions.stream().anyMatch(value -> value.contains(
                "Infobox row links")));
        assertTrue(captions.contains("+ Add fallback source"));
        assertTrue(captions.contains("+ Add category relation"));
        assertTrue(captions.contains("+ Add evidence recipe"));

        long projectedBindings = nodes.stream()
                .filter(FieldConfigurationProjectionTest::bindingRoot).count();
        long inventoryBindings = ConfigurationReferenceInventory.of(project)
                .bindingsStoredAt(ConfigurationReferenceInventory.fieldSite(
                        owner, "members")).size();
        assertEquals(inventoryBindings, projectedBindings,
                "the tree and inventory must enumerate the same stored bindings");
    }

    @Test void contextualSourcesAreSeparateStableBranchesAndProjectionDoesNotMutate()
            throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedClassModel name = new GeneratedClassModel("Name");
        GeneratedFieldModel structuredName = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        structuredName.entityClassName("Name");
        GeneratedFieldModel text = name.addField(
                "text", FieldType.TEXT, FieldCardinality.SINGLE);
        project.rootClass(person);
        project.addClass(name);
        project.ensureDeclarationIdentities();

        text.sourceBindings().add(new SourceBinding(
                SourceBindingTarget.fieldValue("Name", "text",
                        SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe("wikipedia", "article-introduction", Map.of(
                        "projection", "LEAD_SENTENCE"))));
        text.sourceBindings().add(new SourceBinding(new SourceBindingTarget(
                domain.datasource.api.BindingScope.FIELD_VALUE, "Name", "text",
                SourceBindingSlot.PRIMARY_FIELD_VALUE, name.declarationId(),
                "Person", "structuredName", person.declarationId()),
                new SourceRecipe("wikipedia", "article-introduction", Map.of(
                        "projection", "LEAD_PARAGRAPH"))));
        List<SourceBinding> before = List.copyOf(text.sourceBindings());
        assertEquals(2, ConfigurationReferenceInventory.of(project).bindingSites().size());

        ConfigurationNode first = FieldConfigurationProjection.project(
                project, name, text, Datasources.standard());
        ConfigurationNode second = FieldConfigurationProjection.project(
                project, name, text, Datasources.standard());

        assertEquals(describe(first), describe(second));
        assertEquals(before, text.sourceBindings());
        List<String> captions = flatten(first).stream()
                .map(ConfigurationNode::caption).toList();
        assertTrue(captions.stream().anyMatch(value -> value.startsWith("Primary: ")
                && value.contains("Wikipedia")), captions.toString());
        assertTrue(captions.contains("When produced by Person.structuredName"));
        assertEquals(2, flatten(first).stream()
                .filter(FieldConfigurationProjectionTest::bindingRoot).count());
        assertTrue(flatten(first).stream()
                .filter(node -> node.caption().equals("When produced by Person.structuredName")
                        || node.caption().equals("+ Add fallback source"))
                .allMatch(node -> node.sourceContext() != null),
                "structural and available nodes must retain the source context they edit");

        List<String> bindingKeys = flatten(first).stream()
                .filter(FieldConfigurationProjectionTest::bindingRoot)
                .map(value -> value.id().key()).toList();
        assertEquals(2, bindingKeys.stream().distinct().count(),
                "shared and contextual bindings need different stable identities");
    }

    /** An image field was offered "+ Add category relation", which fills Wikidata
     *  entities. Unconfigured slots follow the same type rules validation applies. */
    @Test void onlySlotsTheFieldTypeCanUseAreOffered() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/egyptiangods/egyptiangods.model.json"));
        GeneratedClassModel deity = project.findClass("EgyptianDeity");
        GeneratedFieldModel image = deity.fields().stream()
                .filter(field -> field.name().equals("image")).findFirst().orElseThrow();
        assertEquals(FieldType.IMAGE, image.type());

        List<String> captions = new ArrayList<>();
        collectCaptions(FieldConfigurationProjection.project(
                project, deity, image, Datasources.standard()), captions);

        assertFalse(captions.contains("+ Add category relation"));
        assertTrue(captions.contains("+ Add fallback source"),
                "an infobox or DBpedia value can still fill an image");
    }

    private static void collectCaptions(ConfigurationNode node, List<String> out) {
        out.add(node.caption());
        node.children().forEach(child -> collectCaptions(child, out));
    }

    @Test void optionalBlankRecipeParametersAreAbsent() {
        SourceBinding binding = new SourceBinding(
                SourceBindingTarget.fieldValue("Group", "members",
                        SourceBindingSlot.CANDIDATE_EVIDENCE),
                new SourceRecipe("wikipedia", "article-table-row-instances", Map.of(
                        "section", "Members",
                        "column", "Name",
                        "rowTargetClass", "Member",
                        "rowDetailsField", "details",
                        "rowSourceField", "source")));
        var operation = binding.resolve(Datasources.standard());

        List<String> captions = flatten(operation.configurationElements(binding)).stream()
                .map(domain.datasource.api.ConfigurationElement::caption).toList();
        assertFalse(captions.stream().anyMatch(value -> value.endsWith(": none")));
    }

    @Test void candidateEvidenceIdentityIsItsPositionWithinThatSlot() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/egyptiangods/egyptiangods.model.json"));
        GeneratedClassModel owner = project.findClass("DeityGroup");
        GeneratedFieldModel field = owner.fields().getFirst();
        List<ConfigurationNode.Id> before = candidateBindingIds(
                FieldConfigurationProjection.project(
                        project, owner, field, Datasources.standard()));

        field.sourceBindings().add(0, new SourceBinding(
                SourceBindingTarget.fieldValue("DeityGroup", "members",
                        SourceBindingSlot.FALLBACK_FIELD_VALUE),
                new SourceRecipe("wikipedia", "article-introduction", Map.of())));

        assertEquals(before, candidateBindingIds(FieldConfigurationProjection.project(
                project, owner, field, Datasources.standard())));
    }

    @Test void referenceIdentityDoesNotDependOnAnotherReferencesPosition() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedClassModel organisation = new GeneratedClassModel("Organisation");
        GeneratedFieldModel employer = person.addField(
                "employer", FieldType.ENTITY, FieldCardinality.SINGLE);
        employer.entityClassName("Organisation");
        GeneratedFieldModel employees = organisation.addField(
                "employees", FieldType.ENTITY, FieldCardinality.COLLECTION);
        employees.entityClassName("Person");
        employees.mapping().inverseField("employer");
        GeneratedFieldModel formerEmployees = organisation.addField(
                "formerEmployees", FieldType.ENTITY, FieldCardinality.COLLECTION);
        formerEmployees.entityClassName("Person");
        formerEmployees.mapping().inverseField("employer");
        project.rootClass(person);
        project.addClass(organisation);
        project.ensureDeclarationIdentities();

        ConfigurationNode before = FieldConfigurationProjection.project(
                project, person, employer, Datasources.standard());
        ConfigurationNode.Id formerEmployeesReference = flatten(before).stream()
                .filter(node -> node.kind() == ConfigurationNode.Kind.REFERENCE)
                .filter(node -> node.caption().contains("formerEmployees"))
                .map(ConfigurationNode::id).findFirst().orElseThrow();

        organisation.fields().remove(employees);

        ConfigurationNode after = FieldConfigurationProjection.project(
                project, person, employer, Datasources.standard());
        assertTrue(flatten(after).stream()
                .anyMatch(node -> node.id().equals(formerEmployeesReference)),
                "removing a preceding reference must not move an existing tree node");
    }

    private static List<ConfigurationNode> flatten(ConfigurationNode root) {
        List<ConfigurationNode> result = new ArrayList<>();
        result.add(root);
        root.children().forEach(child -> result.addAll(flatten(child)));
        return result;
    }

    private static List<domain.datasource.api.ConfigurationElement> flatten(
            List<domain.datasource.api.ConfigurationElement> roots) {
        List<domain.datasource.api.ConfigurationElement> result = new ArrayList<>();
        for (var root : roots) {
            result.add(root);
            result.addAll(flatten(root.children()));
        }
        return result;
    }

    private static List<String> describe(ConfigurationNode root) {
        return flatten(root).stream().map(node -> node.id() + "|" + node.kind()
                + "|" + node.caption()).toList();
    }

    private static boolean bindingRoot(ConfigurationNode node) {
        return node.bindingSite() != null && node.id().key().equals(
                "binding:" + node.bindingSite().bindingSite().name());
    }

    private static List<ConfigurationNode.Id> candidateBindingIds(ConfigurationNode root) {
        return flatten(root).stream().filter(FieldConfigurationProjectionTest::bindingRoot)
                .filter(node -> node.bindingSite().binding().target().slot()
                        == SourceBindingSlot.CANDIDATE_EVIDENCE)
                .map(ConfigurationNode::id).toList();
    }
}
