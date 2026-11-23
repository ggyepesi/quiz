package domain.config;

import domain.datasource.api.SourceBindingSlot;
import org.junit.jupiter.api.Test;
import wikidata.query.template.rule.RuleNodeQueryBuilder;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mythology structure is shared configuration; a mythology domain supplies populations.
 * Norse mythology is the forcing importer because its characters, creatures, places,
 * objects, groups and events all have separate bounded Wikidata classes.
 */
class NorseMythologySavedModelTest {
    private static final File CORE =
            new File("../data/wikidata/mythologycore/mythologycore.model.json");
    private static final File NORSE =
            new File("../data/wikidata/norsemythology/norsemythology.model.json");

    @Test void sharedMythologyStructureHasNoDomainPopulation() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(CORE);

        assertEquals(GeneratedProjectModel.ProjectKind.MODEL, model.projectKind());
        for (String name : List.of("MythologicalEntity", "MythologicalCharacter",
                "MythologicalCreature", "MythologicalLocation", "MythologicalArtifact",
                "MythologicalEvent", "MythologicalGroup")) {
            GeneratedClassModel clazz = model.findClass(name);
            assertNotNull(clazz, name);
            assertEquals(EntityBound.Kind.UNBOUNDED, clazz.membership().kind(), name);
        }
        assertEquals("P18", field(model.findClass("MythologicalEntity"), "image")
                .mapping().propertyPid());
        assertEquals(FieldSourceType.WIKIPEDIA_ARTICLE_LEAD_SENTENCE,
                field(model.findClass("MythologicalEntity"), "summary")
                        .mapping().sourceType());
        assertEquals("P710", field(model.findClass("MythologicalEvent"), "participants")
                .mapping().propertyPid());
        // "Part of" on every entity was almost all "part of Norse mythology"; only a
        // location's place within another location is kept.
        assertTrue(model.findClass("MythologicalEntity").fields().stream()
                .noneMatch(value -> value.name().equals("partOf")));
        GeneratedFieldModel partOf = field(model.findClass("MythologicalLocation"), "partOf");
        assertEquals("P361", partOf.mapping().propertyPid());
        assertEquals("MythologicalLocation", partOf.entityClassName());
        // Works (Eddas, sagas) are members of their own referenced-only kind.
        GeneratedClassModel work = model.findClass("MythologicalWork");
        assertNotNull(work);
        assertEquals("MythologicalEntity", work.baseClassName());
        assertEquals(EntityBound.Kind.UNBOUNDED, work.membership().kind());
        GeneratedFieldModel presentInWork =
                field(model.findClass("MythologicalEntity"), "presentInWork");
        assertEquals("MythologicalWork", presentInWork.entityClassName());
        assertFalse(presentInWork.unclassedEntity());
        GeneratedClassModel group = model.findClass("MythologicalGroup");
        GeneratedFieldModel members = field(group, "members");
        assertEquals("members", group.groupSpec().membersField());
        assertEquals("MythologicalEntity", members.entityClassName());
        assertTrue(members.sourceBindings().stream().anyMatch(binding ->
                binding.target().slot() == SourceBindingSlot.PRIMARY_FIELD_VALUE
                        && binding.recipe().providerId().equals("wikidata")
                        && binding.recipe().operationId().equals("relation-routes")));
        assertTrue(members.sourceBindings().stream().anyMatch(binding ->
                binding.target().slot() == SourceBindingSlot.CANDIDATE_EVIDENCE
                        && binding.recipe().providerId().equals("wikipedia")
                        && binding.recipe().parameter("property").equals("Rendered.Members")));
        assertTrue(GeneratedProjectModelValidator.validate(model).valid());
    }

    @Test void norseDomainBindsEveryConcreteKindToAnExplicitWikidataClass()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(NORSE);

        assertEquals(GeneratedProjectModel.ProjectKind.DOMAIN, model.projectKind());
        assertEquals("Mythology Core", model.findClass("MythologicalEntity").importedFrom());
        // Valkyries (Q131087) are Norse characters that the character class alone missed.
        assertPopulation(model, "NorseCharacter", "MythologicalCharacter",
                "Q16513904", "Q131087");
        assertPopulation(model, "NorseDeity", "NorseCharacter", "Q16513881");
        assertPopulation(model, "Jotunn", "NorseCharacter", "Q24267081");
        assertPopulation(model, "NorseDwarf", "NorseCharacter", "Q2738581");
        assertPopulation(model, "NorseCreature", "MythologicalCreature", "Q24336784");
        assertPopulation(model, "NorseAnimal", "NorseCreature", "Q16514343");
        assertPopulation(model, "NorseLocation", "MythologicalLocation", "Q24334976");
        assertPopulation(model, "NorseArtifact", "MythologicalArtifact", "Q16521098");
        assertPopulation(model, "NorseEvent", "MythologicalEvent", "Q24336490");
        // The group class alone has 5 instances; most Norse groups are typed as character
        // or creature groupings (coverage check, 2026-11-23).
        assertPopulation(model, "NorseGroup", "MythologicalGroup",
                "Q24577906", "Q24335536", "Q24337077");

        GeneratedClassModel event = model.findClass("NorseEvent");
        String query = RuleNodeQueryBuilder.valuesQuery(
                domain.compile.rule.RuleTreeCompiler.compileClass(event, model));
        assertTrue(query.contains("wdt:P279*"),
                "the bounded class includes its Wikidata subclasses");
        assertEquals("members", model.findClass("NorseGroup").groupSpec().membersField());
        var sourcePlan = ModelSourceExecutionPlan.compileStored(
                model, domain.datasource.Datasources.standard());
        assertTrue(sourcePlan.steps().stream().anyMatch(step ->
                step.target().className().equals("MythologicalGroup")
                        && step.target().fieldPath().equals("members")
                        && step.recipe().operationId().equals("relation-routes")),
                "the imported group field keeps the shared executable member routes");
        assertTrue(GeneratedProjectModelValidator.validate(model).valid());
        assertEquals(List.of(BuildOperation.Kind.BUILD_DEPENDENCY,
                        BuildOperation.Kind.GENERATE_PROJECT,
                        BuildOperation.Kind.SAVE_PROJECT_RESULT),
                model.buildOperations().stream().map(BuildOperation::kind).toList());
    }

    private static void assertPopulation(GeneratedProjectModel model, String name,
            String base, String... qids) {
        GeneratedClassModel clazz = model.findClass(name);
        assertNotNull(clazz, name);
        assertEquals(base, clazz.baseClassName(), name);
        assertEquals("P31", clazz.membership().relationPid(), name);
        assertEquals(List.of(qids), clazz.membership().qids(), name);
        assertTrue(clazz.membership().includeDescendants(), name);
        assertTrue(clazz.instanceMapping().limit() <= 500, name);
        String query = RuleNodeQueryBuilder.valuesQuery(
                domain.compile.rule.RuleTreeCompiler.compileClass(clazz, model));
        for (String qid : qids) assertTrue(query.contains("wd:" + qid), query);
    }

    private static GeneratedFieldModel field(GeneratedClassModel owner, String name) {
        return owner.fields().stream().filter(field -> field.name().equals(name))
                .findFirst().orElseThrow();
    }
}
