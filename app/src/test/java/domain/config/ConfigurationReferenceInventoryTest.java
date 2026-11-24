package domain.config;

import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.api.SourceBindingTarget;
import domain.datasource.api.SourceRecipe;
import domain.datasource.schema.FieldType;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationReferenceInventoryTest {

    @Test void oneInventoryCoversModelFieldsRecipesAndDatasourceInputs() {
        GeneratedProjectModel project = tableRowProject();

        ConfigurationReferenceInventory inventory =
                ConfigurationReferenceInventory.of(project);
        Set<ConfigurationReferenceInventory.Relation> relations = inventory.references()
                .stream().map(ConfigurationReferenceInventory.Reference::relation)
                .collect(Collectors.toSet());

        assertTrue(relations.contains(
                ConfigurationReferenceInventory.Relation.ENTITY_FIELD_TARGET));
        assertTrue(relations.contains(
                ConfigurationReferenceInventory.Relation.DATASOURCE_RECIPE_CLASS));
        assertEquals(3, inventory.references().stream().filter(reference ->
                reference.relation()
                        == ConfigurationReferenceInventory.Relation.DATASOURCE_RECIPE_FIELD)
                .count());
        assertTrue(relations.contains(
                ConfigurationReferenceInventory.Relation.DATASOURCE_INPUT));

        ConfigurationReferenceInventory.Reference article = inventory.references().stream()
                .filter(reference -> reference.relation()
                        == ConfigurationReferenceInventory.Relation.DATASOURCE_INPUT)
                .findFirst().orElseThrow();
        assertEquals("DeityGroup", article.target().className());
        assertEquals("article correspondence", article.target().name());
    }

    @Test void classRenameUsesTheSameRecipeReferenceThatNavigationShows() {
        GeneratedProjectModel project = tableRowProject();
        GeneratedClassModel assessor = project.findClass("Assessor");
        assertEquals(4, ConfigurationReferenceInventory.of(project)
                .referencesToClass(assessor).stream().filter(reference ->
                        reference.relation()
                                == ConfigurationReferenceInventory.Relation
                                        .DATASOURCE_RECIPE_CLASS
                        || reference.relation()
                                == ConfigurationReferenceInventory.Relation
                                        .DATASOURCE_RECIPE_FIELD)
                .count());

        assertTrue(project.renameClass("Assessor", "MaatAssessor"));

        SourceBinding binding = project.findClass("DeityGroup").fields().getFirst()
                .sourceBindings().getFirst();
        assertEquals("MaatAssessor", binding.recipe().parameter(
                WikipediaDatasourceProvider.TABLE_ROW_TARGET_CLASS));
        assertTrue(ConfigurationReferenceInventory.of(project)
                .referencesToClass(project.findClass("MaatAssessor")).stream()
                .anyMatch(reference -> reference.relation()
                        == ConfigurationReferenceInventory.Relation
                                .DATASOURCE_RECIPE_FIELD));
    }

    @Test void dependencyNavigationUsesTheInventoryEntityAndOwnedReferences() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        GeneratedFieldModel owned = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        owned.entityClassName("Name");
        owned.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedFieldModel parent = person.addField(
                "parent", FieldType.ENTITY, FieldCardinality.SINGLE);
        parent.entityClassName("Person");
        project.rootClass(person);
        project.addClass(name);

        List<ClassDependencies.Edge> nameDependencies =
                ClassDependencies.dependenciesOf(name, project);
        assertEquals(1, nameDependencies.size());
        assertEquals(ClassDependencies.Kind.OWNED,
                nameDependencies.getFirst().kind());
        assertEquals("structuredName", nameDependencies.getFirst().site());

        assertTrue(ClassDependencies.dependenciesOf(person, project).stream()
                .anyMatch(edge -> edge.kind() == ClassDependencies.Kind.REFERENCES
                        && edge.site().equals("parent")));
    }

    @Test void ownedDependencyRequiresAnOwnedTargetClass() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel owner = new GeneratedClassModel("Owner");
        GeneratedClassModel ordinary = new GeneratedClassModel("Ordinary");
        GeneratedFieldModel malformed = owner.addField(
                "ordinary", FieldType.ENTITY, FieldCardinality.SINGLE);
        malformed.entityClassName("Ordinary");
        malformed.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        project.rootClass(owner);
        project.addClass(ordinary);

        assertTrue(ClassDependencies.dependenciesOf(owner, project).stream()
                .anyMatch(edge -> edge.kind() == ClassDependencies.Kind.REFERENCES
                        && edge.dependency() == ordinary));
        assertTrue(ClassDependencies.dependenciesOf(ordinary, project).isEmpty());
    }

    @Test void referenceValidationReadsTheInventoryProjection() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.baseClassName("MissingBase");
        GeneratedFieldModel parent = person.addField(
                "parent", FieldType.ENTITY, FieldCardinality.SINGLE);
        parent.entityClassName("MissingPerson");
        project.rootClass(person);

        GeneratedProjectModelValidator.ValidationResult result =
                GeneratedProjectModelValidator.validate(project);
        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.message().contains("Base class 'MissingBase' does not exist")));
        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.message().contains(
                        "Referenced entity class 'MissingPerson' is not modeled")));
    }

    private static GeneratedProjectModel tableRowProject() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel group = new GeneratedClassModel("DeityGroup");
        GeneratedClassModel member = new GeneratedClassModel("DeityGroupMember");
        GeneratedClassModel assessor = new GeneratedClassModel("Assessor");
        assessor.baseClassName("DeityGroupMember");
        assessor.addField("rowDetails", FieldType.TEXT, FieldCardinality.COLLECTION);
        GeneratedFieldModel sources = assessor.addField(
                "sources", FieldType.ENTITY, FieldCardinality.COLLECTION);
        sources.entityClassName("DeityGroup");
        GeneratedFieldModel links = assessor.addField(
                "links", FieldType.ENTITY, FieldCardinality.COLLECTION);
        links.entityClassName("DeityGroupMember");

        GeneratedFieldModel members = group.addField(
                "members", FieldType.ENTITY, FieldCardinality.COLLECTION);
        members.entityClassName("DeityGroupMember");
        members.sourceBindings().add(new SourceBinding(
                SourceBindingTarget.fieldValue("DeityGroup", "members",
                        SourceBindingSlot.CANDIDATE_EVIDENCE),
                new SourceRecipe(WikipediaDatasourceProvider.ID,
                        WikipediaDatasourceProvider.ARTICLE_TABLE_ROW_INSTANCES,
                        Map.of(
                                WikipediaDatasourceProvider.TABLE_SECTION, "List of names",
                                WikipediaDatasourceProvider.TABLE_COLUMN, "Name",
                                WikipediaDatasourceProvider.TABLE_ROW_TARGET_CLASS, "Assessor",
                                WikipediaDatasourceProvider.TABLE_ROW_DETAILS_FIELD,
                                        "rowDetails",
                                WikipediaDatasourceProvider.TABLE_ROW_SOURCE_FIELD, "sources",
                                WikipediaDatasourceProvider.TABLE_ROW_REFERENCES_FIELD,
                                        "links"))));
        project.rootClass(group);
        project.addClass(member);
        project.addClass(assessor);
        return project;
    }
}
