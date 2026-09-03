package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Declaring a field on an owned class is ADDITIVE — no membership changes, nothing
 * already downloaded is invalidated — so it must not cost a re-extraction. Enrich
 * materializes the component and fetches only that property, for the QIDs the pool
 * already holds; the only thing it needs from the network is wbgetentities.
 */
class EnrichRunTest {

    @Test void anInvalidModelFailsBeforeAnyAcquisition() {
        GeneratedProjectModel invalid = project();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new wikidata.explore.model.StatementClassSource(
                "DoesNotExist", "P1411"));
        // A statement class states its key; nothing chooses one for it. This is what
        // the editor offers — the triple's own components — accepted explicitly.
        nomination.canonical().keyFields().addAll(
                wikidata.explore.model.StatementIdentity.structuralKey(nomination));
        invalid.addClass(nomination);
        WikidataDynamicObject person = new WikidataDynamicObject("Q42", "Douglas Adams");
        person.type("Person");
        GenerationRun previous = new GenerationRun(
                invalid, 1, null, new ArrayList<>(List.of(person)), null, List.of());
        List<List<String>> asked = new ArrayList<>();

        assertThrows(wikidata.explore.compiled.ProjectModelCompiler
                        .ModelCompilationException.class,
                () -> new GenerationPipeline().enrich(
                        previous, invalid, recording(asked), null));

        assertTrue(asked.isEmpty(),
                "model validation must precede semantic or external acquisition: " + asked);
    }

    @Test void loadsADeclaredComponentFieldOverTheExistingPool() throws Exception {
        GeneratedProjectModel project = project();
        WikidataDynamicObject person = new WikidataDynamicObject("Q42", "Douglas Adams");
        person.type("Person");
        person.typeKey("Person");
        GenerationRun previous = new GenerationRun(
                project, 1, null, new ArrayList<>(List.of(person)), null, List.of());

        GenerationRun enriched = new GenerationPipeline()
                .enrich(previous, project, api(), null);

        WikidataDynamicObject component = enriched.dynamicObjects().stream()
                .filter(o -> "BirthName".equals(o.typeName())).findFirst().orElse(null);
        assertNotNull(component, "the component is materialized: "
                + enriched.dynamicObjects().stream()
                        .map(WikidataDynamicObject::typeName).toList());
        assertEquals("Q42", component.getIdentifier(), "it carries the owner's identity");

        WikidataDynamicObject familyName =
                (WikidataDynamicObject) component.get("familyName");
        assertNotNull(familyName, "the declared property was fetched for the owner's qid");
        assertEquals("Q351735", familyName.getIdentifier());
        assertEquals("Adams", familyName.getDisplayName());

        // Enrich is transactional: the visible previous run is untouched until Apply.
        assertNull(person.get("birthName"),
                "the previous run remains unchanged while the staged result is reviewed");
        assertNull(enriched.remapState(),
                "the stale pre-reify cache is dropped rather than re-transformed later");
    }

    /** A long run must SAY what it is doing: compiling and copying tens of thousands
     *  of objects happen before the first fetch, and in silence the run looks hung. */
    @Test void reportsEachPhaseBeforeItRuns() throws Exception {
        GeneratedProjectModel project = project();
        WikidataDynamicObject person = new WikidataDynamicObject("Q42", "Douglas Adams");
        person.type("Person");
        person.typeKey("Person");
        GenerationRun previous = new GenerationRun(
                project, 1, null, new ArrayList<>(List.of(person)), null, List.of());
        StringBuilder log = new StringBuilder();

        new GenerationPipeline().enrich(previous, project, api(),
                wikidata.explore.extract.GenerationLog.of(log::append));

        String reported = log.toString();
        assertTrue(reported.contains("compiling"), reported);
        assertTrue(reported.contains("1 downloaded object"), reported);
        // Names the declarations it will try, so a run that fetches nothing says why.
        assertTrue(reported.contains("Name.familyName (P734)"), reported);
    }

    /** Being a part is a property of the TYPE, so it must survive the snapshot: reloaded,
     *  it is still kept out of the served datasets. */
    @org.junit.jupiter.api.Test void aPartStaysAPartAcrossTheSnapshot(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        GeneratedProjectModel project = project();
        WikidataDynamicObject person = new WikidataDynamicObject("Q42", "Douglas Adams");
        person.type("Person");
        person.typeKey("Person");
        GenerationRun previous = new GenerationRun(
                project, 1, null, new ArrayList<>(List.of(person)), null, List.of());
        GenerationRun enriched = new GenerationPipeline()
                .enrich(previous, project, api(), null);

        java.io.File file = dir.resolve("enriched.snapshot.json").toFile();
        var store = new wikidata.explore.extract.WikidataDynamicObjectJsonStore();
        store.saveWithFieldGraph(enriched.dynamicObjects(), file);
        var loaded = store.loadAllWithFieldGraph(file);

        assertTrue(loaded.fieldGraph().isPart("BirthName"),
                "the schema records that this type's instances are parts");
        // A part is in the pool because it is reachable, not because it is a root: it
        // must never be offered as a dataset in its own right.
        assertTrue(loaded.fieldGraph().memberTypes().contains("Person"),
                loaded.fieldGraph().memberTypes().toString());
        org.junit.jupiter.api.Assertions.assertFalse(
                loaded.fieldGraph().memberTypes().contains("BirthName"),
                "a nameless part is not a served type: "
                        + loaded.fieldGraph().memberTypes());
        WikidataDynamicObject reloaded = loaded.objects().stream()
                .filter(o -> "BirthName".equals(o.typeName())).findFirst().orElseThrow();
        assertEquals("Douglas Adams — Birth Name", reloaded.getDisplayName(),
                "reloaded, it still says whose view it is and which");
        assertEquals("Q42", reloaded.getIdentifier(), "its identity is unchanged");
    }

    /** A field with no value may simply have no answer in Wikidata — for P734 that is
     *  most people. Without a record of what has been FETCHED, every later run asks for
     *  them all again; the snapshot carries that record so a run asks only for what is
     *  new. */
    @org.junit.jupiter.api.Test void aFetchedDeclarationIsNotFetchedAgain(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        GeneratedProjectModel project = project();
        WikidataDynamicObject person = new WikidataDynamicObject("Q7", "Nameless Person");
        person.type("Person");
        person.typeKey("Person");
        GenerationRun previous = new GenerationRun(
                project, 1, null, new ArrayList<>(List.of(person)), null, List.of());

        java.util.List<java.util.List<String>> asked = new ArrayList<>();
        GenerationRun first = new GenerationPipeline()
                .enrich(previous, project, recording(asked), null);

        assertEquals(1, asked.size(), "asked once for the person with no P734: " + asked);
        assertEquals(List.of("Q7"), asked.getFirst());
        assertEquals(1, first.loadedDeclarations().size(),
                "the run records the declaration it completed");
        assertEquals("BirthName.familyName:P734",
                first.loadedDeclarations().getFirst().key());
        assertEquals(List.of("Q7"),
                first.loadedDeclarations().getFirst().coveredQids());

        java.io.File file = dir.resolve("enriched.snapshot.json").toFile();
        var store = new wikidata.explore.extract.WikidataDynamicObjectJsonStore();
        store.saveWithFieldGraph(first.dynamicObjects(), file, project,
                first.loadedDeclarations());
        var saved = store.loadAllWithFieldGraph(file);
        assertEquals(List.of("Q7"), saved.loadedDeclarations().getFirst().coveredQids(),
                "the snapshot retains identity-level fetch coverage");
        GenerationRun resumed = new GenerationRun(
                project, first.depth(), first.plan(), saved.objects(), null, List.of(),
                null, saved.loadedDeclarations());

        // A second enrich over the same pool: the value is still absent, but the
        // declaration is known, so nothing is asked again.
        asked.clear();
        new GenerationPipeline().enrich(resumed, project, recording(asked), null);
        assertTrue(asked.isEmpty(), "a completed declaration is not re-fetched: " + asked);
    }

    private static WikidataApiClient recording(java.util.List<java.util.List<String>> asked) {
        return new WikidataApiClient(WikidataApiClient.DEFAULT_USER_AGENT) {
            @Override public Map<String, ApiEntity> getEntities(
                    List<String> qids, List<String> pids, BatchLog log) {
                if (pids != null && pids.contains("P734")) asked.add(List.copyOf(qids));
                return Map.of();   // Wikidata has no P734 for this person
            }
            @Override public PartialEntities getEntityClaimsPartial(
                    List<String> qids, List<String> pids, BatchLog log) {
                return new PartialEntities(getEntities(qids, pids, log), 0);
            }
            @Override public PartialEntities getEntitiesBestEffort(
                    List<String> qids, List<String> pids, BatchLog log) {
                return new PartialEntities(getEntities(qids, pids, log), 0);
            }
        };
    }

    /** Answers wbgetentities: Q42 has P734 → Q351735, which is labelled "Adams". */
    private static WikidataApiClient api() {
        return new WikidataApiClient(WikidataApiClient.DEFAULT_USER_AGENT) {
            @Override public Map<String, ApiEntity> getEntities(
                    List<String> qids, List<String> claimPids, BatchLog batchLog) {
                Map<String, ApiEntity> out = new java.util.LinkedHashMap<>();
                for (String qid : qids) {
                    if ("Q42".equals(qid)) {
                        out.put(qid, new ApiEntity("Q42", "Douglas Adams",
                                Map.of("P734", List.of("Q351735")), false, Map.of()));
                    } else if ("Q351735".equals(qid)) {
                        out.put(qid, new ApiEntity("Q351735", "Adams",
                                Map.of(), false, Map.of()));
                    }
                }
                return out;
            }
            @Override public PartialEntities getEntityClaimsPartial(
                    List<String> qids, List<String> pids, BatchLog log) {
                return new PartialEntities(getEntities(qids, pids, log), 0);
            }
            @Override public PartialEntities getEntitiesBestEffort(
                    List<String> qids, List<String> pids, BatchLog log) {
                return new PartialEntities(getEntities(qids, pids, log), 0);
            }
        };
    }

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("people");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().propertyPid("P31");
        person.instanceMapping().sourceQid("Q5");
        GeneratedFieldModel fullname = person.addField(
                "birthName", FieldType.ENTITY, FieldCardinality.SINGLE);
        fullname.entityClassName("BirthName");
        fullname.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);

        GeneratedClassModel name = new GeneratedClassModel("BirthName");
        name.ownedClass(true);
        GeneratedFieldModel familyName = name.addField(
                "familyName", FieldType.ENTITY, FieldCardinality.SINGLE);
        familyName.entityClassName("FamilyName");
        familyName.mapping().propertyPid("P734");

        project.rootClass(person);
        project.addClass(name);
        project.addClass(new GeneratedClassModel("FamilyName"));
        project.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));
        return project;
    }
}
