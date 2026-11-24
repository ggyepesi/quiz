package domain.config;

import domain.datasource.api.SourceBindingSlot;
import org.junit.jupiter.api.Test;
import wikidata.query.template.rule.RuleNodeQueryBuilder;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mythology structure is shared configuration; a mythology domain supplies populations.
 * Norse mythology is the forcing importer because its characters, creatures, places,
 * objects, works, groups and events all have separate bounded Wikidata classes.
 */
class NorseMythologySavedModelTest {
    private static final File CORE =
            new File("../data/wikidata/mythologycore/mythologycore.model.json");
    private static final File NORSE =
            new File("../data/wikidata/norsemythology/norsemythology.model.json");

    @Test void sharedMythologyStructureHasNoDomainPopulation() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(CORE);

        assertEquals(GeneratedProjectModel.ProjectKind.MODEL, model.projectKind());
        for (String name : List.of("MythologicalEntity", "MythologicalBeing",
                "MythologicalCharacter", "MythologicalCreature", "MythologicalLocation",
                "MythologicalArtifact", "MythologicalEvent", "MythologicalGroup")) {
            GeneratedClassModel clazz = model.findClass(name);
            assertNotNull(clazz, name);
            assertEquals(EntityBound.Kind.UNBOUNDED, clazz.membership().kind(), name);
        }
        assertEquals("P18", field(model.findClass("MythologicalEntity"), "image")
                .mapping().propertyPid());
        assertEquals(FieldSourceType.WIKIPEDIA_ARTICLE_LEAD_SENTENCE,
                field(model.findClass("MythologicalEntity"), "summary")
                        .mapping().sourceType());
        GeneratedFieldModel captions = field(
                model.findClass("MythologicalEntity"), "captions");
        var captionBinding = captions.sourceBindings().stream()
                .filter(binding -> binding.target().slot()
                        == SourceBindingSlot.CANDIDATE_EVIDENCE)
                .findFirst().orElseThrow();
        assertEquals(domain.datasource.evidence.WikipediaArticle.Block.Kind.CAPTION,
                domain.datasource.wikipedia.WikipediaDatasourceProvider
                        .articlePassageSpec(captionBinding).blockKind());
        assertEquals("P710", field(model.findClass("MythologicalEvent"), "participants")
                .mapping().propertyPid());
        // "Part of" on every entity was almost all "part of Norse mythology"; only a
        // location's place within another location is kept.
        assertTrue(model.findClass("MythologicalEntity").fields().stream()
                .noneMatch(value -> value.name().equals("partOf")));
        GeneratedClassModel being = model.findClass("MythologicalBeing");
        assertEquals("MythologicalEntity", being.baseClassName());
        assertEquals("MythologicalBeing",
                model.findClass("MythologicalCharacter").baseClassName());
        assertEquals("MythologicalBeing",
                model.findClass("MythologicalCreature").baseClassName());
        assertEquals("P21", field(being, "sexOrGender").mapping().propertyPid());
        assertTrue(field(being, "sexOrGender").unclassedEntity());
        for (String relation : List.of("father", "mother", "child")) {
            assertEquals("MythologicalBeing", field(being, relation).entityClassName());
        }
        GeneratedFieldModel narrativeText = field(being, "narrativeText");
        assertTrue(narrativeText.sourceBindings().stream().anyMatch(binding ->
                binding.target().slot() == SourceBindingSlot.CANDIDATE_EVIDENCE
                        && binding.recipe().providerId().equals("wikipedia")
                        && binding.recipe().operationId().equals("article-passages")));
        GeneratedFieldModel stories = field(being, "stories");
        assertEquals("MythologicalEvent", stories.entityClassName());
        assertTrue(stories.sourceBindings().stream().anyMatch(binding ->
                binding.target().slot() == SourceBindingSlot.CANDIDATE_EVIDENCE
                        && binding.recipe().providerId().equals("wikipedia")
                        && binding.recipe().operationId().equals("article-passage-instances")
                        && binding.recipe().parameter("passageSourceField")
                                .equals("participants")
                        && binding.recipe().parameter("passageTextField").equals("summary")));
        GeneratedFieldModel partOf = field(model.findClass("MythologicalLocation"), "partOf");
        assertEquals("P361", partOf.mapping().propertyPid());
        assertEquals("MythologicalLocation", partOf.entityClassName());
        GeneratedFieldModel parentStory =
                field(model.findClass("MythologicalEvent"), "parentStory");
        assertEquals("P361", parentStory.mapping().propertyPid());
        assertEquals("MythologicalEvent", parentStory.entityClassName());
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
        GeneratedFieldModel realms = field(group, "realms");
        assertEquals("MythologicalLocation", realms.entityClassName());
        var realmRoutes = domain.datasource.wikidata.WikidataRelationRouteSpec.parse(
                realms.sourceBindings().stream()
                        .filter(binding -> binding.target().slot()
                                == SourceBindingSlot.PRIMARY_FIELD_VALUE)
                        .findFirst().orElseThrow().recipe().parameter("routes"));
        assertEquals(List.of(
                        "Residences of listed members: OUTGOING P527, OUTGOING P551",
                        "Residences of members with part of group: INCOMING P361, "
                                + "OUTGOING P551",
                        "Residences of members of group: INCOMING P463, OUTGOING P551"),
                realmRoutes.routes().stream().map(route -> route.label() + ": "
                        + route.steps().stream().map(step -> step.direction() + " "
                                + step.relation().relationId())
                        .collect(java.util.stream.Collectors.joining(", "))).toList());
        assertTrue(GeneratedProjectModelValidator.validate(model).valid());
    }

    @Test void norseDomainBindsEveryConcreteKindToAnExplicitWikidataClass()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(NORSE);

        assertEquals(GeneratedProjectModel.ProjectKind.DOMAIN, model.projectKind());
        assertEquals("Mythology Core", model.findClass("MythologicalEntity").importedFrom());
        assertEquals("Mythology Core", model.findClass("MythologicalBeing").importedFrom());
        // Valkyries (Q131087) and the Norse-specific Thurse branch (Q4466392) are
        // character carriers that the broad Norse-character class alone misses.
        assertPopulation(model, "NorseCharacter", "MythologicalCharacter",
                "Q16513904", "Q131087", "Q4466392");
        assertPopulation(model, "NorseDeity", "NorseCharacter", "Q16513881");
        // Wikidata does not place Jötunn under "giant"; the model does: every jötunn is a
        // NorseGiant, while Jotunn keeps its own Jötunn population, not narrowed by the
        // giant condition.
        assertPopulation(model, "Jotunn", "NorseGiant", "Q24267081");
        assertTrue(model.isSameOrSubclass("Jotunn", "NorseCharacter"));
        GeneratedClassModel giant = model.findClass("NorseGiant");
        assertNotNull(giant);
        assertEquals("NorseCharacter", giant.baseClassName());
        assertEquals(EntityBound.Kind.UNBOUNDED, giant.membership().kind());
        assertEquals(SubclassCondition.propertyValue("P31", "Q3707571", true),
                giant.subclassCondition());
        assertEquals(MembershipPattern.CONDITIONED_SUBCLASS,
                MembershipPattern.of(giant, model));
        String giantQuery = RuleNodeQueryBuilder.valuesQuery(
                domain.compile.rule.RuleTreeCompiler.compileClass(giant, model));
        for (String qid : List.of("Q16513904", "Q131087", "Q4466392", "Q3707571")) {
            assertTrue(giantQuery.contains("wd:" + qid), giantQuery);
        }
        assertPopulation(model, "NorseDwarf", "NorseCharacter", "Q2738581");
        GeneratedClassModel creature = model.findClass("NorseCreature");
        assertNotNull(creature);
        assertEquals("MythologicalCreature", creature.baseClassName());
        assertEquals(EntityBound.Kind.UNBOUNDED, creature.membership().kind());
        assertTrue(creature.populationSource() == null,
                "the abstract creature base must not generate a one-item population");
        assertPopulation(model, "NorseAnimal", "NorseCreature",
                "Q16514343", "Q24336784");
        assertPopulation(model, "NorseLocation", "MythologicalLocation", "Q24334976");
        GeneratedClassModel realm = model.findClass("NorseRealm");
        assertNotNull(realm);
        assertEquals("NorseLocation", realm.baseClassName());
        assertEquals(EntityBound.Kind.UNBOUNDED, realm.membership().kind());
        // A realm is a Norse location that Wikidata states is part of Norse cosmology
        // (Q1798538): a condition on the location population, not a hand-kept QID list.
        // An entity-kind rule cannot do this: it only types entities whose class is not
        // already known, and every world is already a NorseLocation.
        assertTrue(realm.seedQids().isEmpty(), "no hand-kept world list");
        assertEquals(SubclassCondition.propertyValue("P361", "Q1798538"),
                realm.subclassCondition());
        assertTrue(model.entityKindRules().stream()
                .noneMatch(rule -> rule.className().equals("NorseRealm")));
        String realmQuery = RuleNodeQueryBuilder.valuesQuery(
                domain.compile.rule.RuleTreeCompiler.compileClass(realm, model));
        assertTrue(realmQuery.contains("wdt:P361 wd:Q1798538"), realmQuery);
        assertTrue(realmQuery.contains("wd:Q24334976"), realmQuery);
        // It has a population (its base's, narrowed), so it is neither "Unconfigured" in
        // the editor nor a schema-only base whose members a save may drop unguarded.
        assertEquals(MembershipPattern.CONDITIONED_SUBCLASS, MembershipPattern.of(realm, model));
        assertEquals("Subclass by condition (P361 = Q1798538)",
                MembershipPattern.describe(realm, model));
        assertFalse(MembershipPattern.schemaOnlyBase(realm, model));
        assertPopulation(model, "NorseArtifact", "MythologicalArtifact", "Q16521098");
        assertPopulation(model, "NorseEvent", "MythologicalEvent", "Q24336490");
        // These are deliberately Norse-specific work classes. The global literary-work
        // class would admit an uncontrolled population merely to retain P1441 links.
        assertPopulation(model, "NorseWork", "MythologicalWork",
                "Q24457184", "Q24491885", "Q20022348");
        // The texts Norse entities appear in (Eddas, sagas) are typed only "literary
        // work", too broad to populate from. They are a sibling seeded kind: seeds on
        // NorseWork itself would INTERSECT its class membership and drop them again.
        GeneratedClassModel literary = model.findClass("NorseLiteraryWork");
        assertNotNull(literary);
        assertEquals("MythologicalWork", literary.baseClassName());
        assertEquals(MembershipPattern.SEEDED, MembershipPattern.of(literary, model));
        assertTrue(literary.seedQids().containsAll(List.of("Q205874", "Q205882", "Q733340")),
                "Poetic Edda, Prose Edda and Volsunga saga");
        String literaryQuery = RuleNodeQueryBuilder.valuesQuery(
                domain.compile.rule.RuleTreeCompiler.compileClass(literary, model));
        assertFalse(literaryQuery.contains("wdt:P31 ?root"),
                "no inherited class restriction narrows the seeds");
        // The group class alone has 5 instances; most Norse groups are typed as character
        // or creature groupings (coverage check, 2026-11-23).
        assertPopulation(model, "NorseGroup", "MythologicalGroup",
                "Q24577906", "Q24335536", "Q24337077");

        GeneratedClassModel event = model.findClass("NorseEvent");
        String query = RuleNodeQueryBuilder.valuesQuery(
                domain.compile.rule.RuleTreeCompiler.compileClass(event, model));
        assertTrue(query.contains("wdt:P279*"),
                "the bounded class includes its Wikidata subclasses");
        String animalQuery = RuleNodeQueryBuilder.valuesQuery(
                domain.compile.rule.RuleTreeCompiler.compileClass(
                        model.findClass("NorseAnimal"), model));
        for (String property : List.of("P21", "P22", "P25", "P40")) {
            assertTrue(animalQuery.contains("wdt:" + property),
                    "animals inherit the shared mythological-being field " + property);
        }
        assertEquals("members", model.findClass("NorseGroup").groupSpec().membersField());
        var sourcePlan = ModelSourceExecutionPlan.compileStored(
                model, domain.datasource.Datasources.standard());
        // A conditioned subclass runs its base's location query, narrowed by its own
        // P361 = Norse cosmology condition (asserted on the compiled query above): the
        // 2026-11-24 build produced exactly 12 realms. Evidence-derived kinds, which must
        // not inherit a query, are covered by PopulationSourceExecutionTest.
        assertNotNull(sourcePlan.step(
                        domain.datasource.api.SourceBindingTarget
                                .classPopulation("NorseRealm")),
                "a conditioned subclass is acquired through its base's population");
        assertTrue(domain.build.PopulationSourceExecution.resolve(
                        model, realm, sourcePlan).available());
        assertTrue(sourcePlan.steps().stream().anyMatch(step ->
                step.target().className().equals("MythologicalGroup")
                        && step.target().fieldPath().equals("members")
                        && step.recipe().operationId().equals("relation-routes")),
                "the imported group field keeps the shared executable member routes");
        assertTrue(sourcePlan.steps().stream().anyMatch(step ->
                step.target().className().equals("MythologicalGroup")
                        && step.target().fieldPath().equals("realms")
                        && step.recipe().operationId().equals("relation-routes")),
                "the imported group field keeps the shared executable realm routes");
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
