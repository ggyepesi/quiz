package wikidata.explore.model;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.RuleTreeExtractor;
import wikidata.explore.generation.CompiledPipelineRun;
import wikidata.explore.generation.PipelineRequest;
import wikidata.explore.rule.RuleTreeCompiler;

import java.io.File;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped Solar System model remains an explicit, bounded object graph. */
class SolarSystemSavedModelTest {

    private static final File MODEL =
            new File("../data/wikidata/solarsystem/solarsystem.model.json");
    private static final List<String> PLANETS = List.of(
            "Q308", "Q313", "Q2", "Q111", "Q319", "Q193", "Q324", "Q332");
    private static final String JOVIAN_MOON_KIND = "Q61702557";

    @Test void rootsAreExplicitAndMoonsAreBoundedByTheirConfiguredParents() throws Exception {
        GeneratedProjectModel model = load();

        assertEquals(List.of("Q544"), model.findClass("SolarSystem").seedQids());
        assertEquals(List.of("Q525"), model.findClass("Sun").seedQids());
        assertEquals(PLANETS, model.findClass("Planet").seedQids());
        assertEquals(List.of("Q3504248", "Q30014"),
                model.findClass("PlanetType").seedQids());

        GeneratedClassModel moon = model.findClass("Moon");
        assertTrue(moon.seedQids().isEmpty());
        assertEquals(EntityBound.Kind.RELATION, moon.membership().kind());
        assertEquals("P397", moon.membership().relationPid());
        assertEquals(PLANETS, moon.membership().qids());
        assertEquals(SubclassCondition.propertyValue(
                        "P31", "Q109645860", true),
                moon.subclassCondition());
        assertEquals(MembershipPattern.MULTI_TARGET_RELATION,
                MembershipPattern.of(moon, model));
    }

    @Test void celestialBodiesShareOneBaseClassAndItsFields() throws Exception {
        GeneratedProjectModel model = load();
        GeneratedClassModel celestialBody = model.findClass("CelestialBody");

        assertNotNull(celestialBody);
        assertEquals("Astronomy", celestialBody.importedFrom());
        assertEquals("CelestialBody", model.findClass("Sun").baseClassName());
        assertEquals("CelestialBody", model.findClass("Planet").baseClassName());
        assertEquals("CelestialBody", model.findClass("Moon").baseClassName());
        assertField(model, "CelestialBody", "image", "P18");
        assertField(model, "CelestialBody", "radius", "P2120");
        assertField(model, "CelestialBody", "diameter", "P2386");
        GeneratedFieldModel discovery = field(model, "CelestialBody", "discovery");
        assertEquals("Discovery", discovery.entityClassName());
        assertEquals(FieldProductionKind.OWNED_COMPONENT,
                discovery.mapping().productionKind());
        assertEquals("P575", field(model, "Discovery", "date")
                .mapping().propertyPid());
        assertEquals("P61", field(model, "Discovery", "discoverers")
                .mapping().propertyPid());
        assertEquals(FieldCardinality.COLLECTION,
                field(model, "Discovery", "discoverers").cardinality());
        assertTrue(model.findClass("Planet").effectiveFields(model).stream()
                .anyMatch(field -> "image".equals(field.name())));
        assertTrue(model.findClass("Moon").effectiveFields(model).stream()
                .anyMatch(field -> "radius".equals(field.name())));
        assertTrue(model.findClass("Moon").effectiveFields(model).stream()
                .anyMatch(field -> "discovery".equals(field.name())));
        assertTrue(model.findClass("Moon").effectiveFields(model).stream()
                .anyMatch(field -> "discovery".equals(field.name())));
    }

    @Test void humanDiscoverersUseTheSharedEvidenceBasedPersonKind() throws Exception {
        GeneratedProjectModel model = load();
        GeneratedClassModel person = model.findClass("Person");
        GeneratedFieldModel discoverer = field(model, "Discovery", "discoverers");

        assertNotNull(person);
        assertEquals("Discoverer", discoverer.entityClassName());
        assertEquals("Person", person.importedFrom());
        assertEquals("Discovery", model.findClass("Discoverer").importedFrom());

        EntityKindRule rule = model.entityKindRules().stream()
                .filter(candidate -> "Person".equals(candidate.className()))
                .findFirst().orElseThrow();
        assertEquals(person.declarationId(), rule.classId());
        assertEquals("P31", rule.propertyPid());
        assertEquals(List.of("Q5"), rule.evidenceQids());
        EntityRepresentationRule representation = model.entityRepresentationRules()
                .stream().filter(candidate -> candidate.roleClassName()
                        .equals("Discoverer")).findFirst().orElseThrow();
        assertEquals("Person", representation.representationClassName());
        assertEquals("Discovery", representation.importedFrom());
    }

    @Test void wikipediaCorrespondenceIsConfiguredOnceOnEachSchemaRoot()
            throws Exception {
        GeneratedProjectModel model = load();

        for (String name : List.of(
                "SolarSystem", "CelestialBody", "PlanetType", "MoonKind")) {
            assertNotNull(ClassSourceBindings.articleCorrespondence(
                    model.findClass(name)), name);
        }
        assertTrue(ArticleCorrespondencePlan.classes(model,
                ModelSourceExecutionPlan.compile(model, datasource.Datasources.standard()))
                .containsAll(List.of("Sun", "Planet", "Moon")),
                "CelestialBody correspondence is inherited by all three subclasses");
    }

    @Test void relationshipsUseTheClaimDirectionAndRepeatTheirBounds() throws Exception {
        GeneratedProjectModel model = load();

        assertReference(model, "Sun", "solarSystem", "SolarSystem", "P361", "Q544");
        assertReference(model, "Planet", "parentBody", "Sun", "P397", "Q525");
        assertReference(model, "Moon", "parentBody", "Planet", "P397",
                PLANETS.toArray(String[]::new));
        assertReference(model, "Planet", "planetType", "PlanetType", "P31",
                "Q3504248", "Q30014");

        GeneratedFieldModel moons = field(model, "Planet", "moons");
        assertEquals("Moon", moons.entityClassName());
        assertEquals(FieldCardinality.COLLECTION, moons.cardinality());
        assertEquals(FieldProductionKind.INVERT, moons.mapping().productionKind());
        assertEquals("parentBody", moons.mapping().inverseField());
        assertTrue(moons.mapping().propertyPid().isBlank(),
                "the inverse is local; it must not issue incoming P397 queries");
    }

    @Test void jovianMoonKindSupplementsTheDirectParentBodyPopulation() throws Exception {
        GeneratedProjectModel model = load();
        GeneratedClassModel moonKind = model.findClass("MoonKind");

        assertNotNull(moonKind);
        assertEquals(List.of(JOVIAN_MOON_KIND), moonKind.seedQids());
        assertReference(model, "MoonKind", "parentBody", "Planet", "P397", "Q319");

        GeneratedFieldModel typedMoons = field(model, "MoonKind", "moons");
        assertEquals("Moon", typedMoons.entityClassName());
        assertEquals(FieldCardinality.COLLECTION, typedMoons.cardinality());
        assertEquals("P31", typedMoons.mapping().propertyPid());
        assertEquals(RuleDirection.ITEM_TO_ROOT, typedMoons.mapping().direction());
        assertEquals(FieldProductionKind.CHILD_OBJECTS,
                typedMoons.mapping().productionKind());
        assertEquals(EdgeMembershipMode.NONE, typedMoons.edgeMembership(),
                "P31 = moon of Jupiter is the bound; requiring P397 again would lose "
                        + "the additional moons this route exists to retain");

        GeneratedFieldModel planetKinds = field(model, "Planet", "moonKinds");
        assertEquals("MoonKind", planetKinds.entityClassName());
        assertEquals(FieldProductionKind.INVERT, planetKinds.mapping().productionKind());
        assertEquals("parentBody", planetKinds.mapping().inverseField());

        GeneratedFieldModel allMoons = field(model, "Planet", "allMoons");
        assertEquals("Moon", allMoons.entityClassName());
        assertEquals(FieldCardinality.COLLECTION, allMoons.cardinality());
        assertEquals(FieldProductionKind.UNION,
                allMoons.mapping().productionKind());
        assertEquals(List.of("moons", "moonKinds.moons"),
                allMoons.mapping().unionSourcePaths());

        GeneratedFieldModel kind = field(model, "Moon", "moonKind");
        assertEquals("MoonKind", kind.entityClassName());
        assertEquals(FieldCardinality.COLLECTION, kind.cardinality());
        assertEquals(FieldProductionKind.INVERT, kind.mapping().productionKind());
        assertEquals("moons", kind.mapping().inverseField());

        String preview = String.join("\n", new RuleTreeExtractor(null).previewQueries(
                RuleTreeCompiler.compileClass(moonKind, model), moonKind.generationDepth()));
        assertTrue(preview.contains("wd:" + JOVIAN_MOON_KIND), preview);
        assertTrue(preview.contains("wdt:P31"), preview);
    }

    @Test void moonDataUsesOutgoingClaimsOnTheBoundedPopulation() throws Exception {
        GeneratedProjectModel model = load();

        assertField(model, "Moon", "orbitalPeriod", "P2146");
    }

    @Test void savedModelLoadsValidatesAndCompilesForGeneration() throws Exception {
        GeneratedProjectModel model = load();

        GeneratedProjectModelValidator.ValidationResult structural =
                GeneratedProjectModelValidator.validate(model);
        GeneratedProjectModelValidator.ValidationResult acquisition =
                GeneratedProjectModelValidator.validateForAcquisition(model);
        assertTrue(structural.valid(), structural.format());
        assertTrue(acquisition.valid(), acquisition.format());
        assertFalse(CompiledPipelineRun.compile(PipelineRequest.generateDomain(model)).blocked());
        assertEquals(List.of(
                BuildOperation.Kind.GENERATE_PROJECT,
                BuildOperation.Kind.SAVE_PROJECT_RESULT),
                model.buildOperations().stream().map(BuildOperation::kind).toList());
    }

    @Test void everyGeneratedPopulationPreviewIsBounded() throws Exception {
        GeneratedProjectModel model = load();

        for (GeneratedClassModel clazz : model.classes()) {
            if (clazz == model.findClass("CelestialBody")) continue;
            if (MembershipPattern.of(clazz, model) == MembershipPattern.REFERENCED) continue;
            String preview = String.join("\n", new RuleTreeExtractor(null).previewQueries(
                    RuleTreeCompiler.compileClass(clazz, model), clazz.generationDepth()));
            if (clazz == model.findClass("Moon")) {
                assertTrue(preview.contains("wdt:P397"), preview);
                assertTrue(preview.contains("wdt:P31"), preview);
                assertTrue(preview.contains("wdt:P279*"), preview);
                assertTrue(preview.contains("wd:Q109645860"), preview);
                for (String planet : PLANETS) {
                    assertTrue(preview.contains("wd:" + planet),
                            () -> "Moon preview omits " + planet + ":\n" + preview);
                }
            } else {
                assertTrue(preview.contains("VALUES ?value"),
                        () -> clazz.className() + " preview is not explicitly bounded:\n" + preview);
                for (String qid : clazz.seedQids()) {
                    assertTrue(preview.contains("wd:" + qid),
                            () -> clazz.className() + " preview omits " + qid + ":\n" + preview);
                }
            }
        }
    }

    private static GeneratedProjectModel load() throws Exception {
        assertTrue(MODEL.isFile(), MODEL.getPath());
        return new GeneratedProjectModelStore().load(MODEL);
    }

    private static void assertReference(GeneratedProjectModel model,
            String ownerName, String fieldName, String targetName, String pid,
            String... allowedQids) {
        GeneratedClassModel owner = model.findClass(ownerName);
        assertNotNull(owner);
        GeneratedFieldModel field = owner.fields().stream()
                .filter(candidate -> fieldName.equals(candidate.name()))
                .findFirst().orElseThrow();
        assertEquals(targetName, field.entityClassName());
        assertEquals(pid, field.mapping().propertyPid());
        assertEquals(RuleDirection.ROOT_TO_ITEM, field.mapping().direction());
        assertEquals(Set.of(allowedQids), field.mapping().allowedQids());
    }

    private static void assertField(GeneratedProjectModel model,
            String ownerName, String fieldName, String pid) {
        GeneratedFieldModel field = field(model, ownerName, fieldName);
        assertEquals(pid, field.mapping().propertyPid());
        assertEquals(RuleDirection.ROOT_TO_ITEM, field.mapping().direction());
    }

    private static GeneratedFieldModel field(GeneratedProjectModel model,
            String ownerName, String fieldName) {
        GeneratedClassModel owner = model.findClass(ownerName);
        assertNotNull(owner);
        return owner.fields().stream()
                .filter(candidate -> fieldName.equals(candidate.name()))
                .findFirst().orElseThrow();
    }
}
