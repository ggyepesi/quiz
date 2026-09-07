package wikidata.explore.extract;

import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import wikidata.explore.extract.WikidataDynamicObject;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.transform.app.SnapshotDomain;
import domain.DomainField;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotFieldGraphStoreTest {

    @TempDir File dir;

    @Test void generatedModelEnrichesOnlyTheFieldGraph() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel state = new GeneratedClassModel("State");
        state.addField("population", FieldType.NUMBER, FieldCardinality.SINGLE);
        state.addField("tags", FieldType.STRING, FieldCardinality.COLLECTION);
        state.addField("admissionDate", FieldType.DATE, FieldCardinality.SINGLE);
        model.rootClass(state);

        WikidataDynamicObject instance = wdo("Q1", "State", false);
        instance.put("population", 10L);
        instance.put("tags", new ArrayList<>());
        List<WikidataDynamicObject> objects = new ArrayList<>(List.of(instance));

        File fallbackFile = new File(dir, "generated-fallback.snapshot.json");
        File richFile = new File(dir, "generated-rich.snapshot.json");
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(objects, fallbackFile);
        store.saveWithFieldGraph(objects, richFile, model);

        ObjectNode fallback = (ObjectNode) store.mapper().readTree(fallbackFile);
        ObjectNode rich = (ObjectNode) store.mapper().readTree(richFile);
        fallback.remove("fieldGraph");
        rich.remove("fieldGraph");
        assertEquals(fallback, rich,
                "model enrichment must not change roots or entity values");

        SnapshotFieldGraph fallbackGraph =
                store.loadAllWithFieldGraph(fallbackFile).fieldGraph();
        SnapshotFieldGraph richGraph =
                store.loadAllWithFieldGraph(richFile).fieldGraph();
        assertFalse(fallbackGraph.types.get("State").fields
                .containsKey("admissionDate"));
        SnapshotFieldGraph.TypeShape richState = richGraph.types.get("State");
        assertTrue(richState.fields.containsKey("admissionDate"));
        assertEquals("Date", richState.fields.get("admissionDate").typeLabel());
        assertEquals("Collection<String>", richState.fields.get("tags").typeLabel());
        var source = richGraph.fieldSchema("State", java.util.Set.of())
                .field("wikidataSource");
        assertEquals("Wikidata source", source.label());
        assertEquals(objectview.field.FieldRole.PROVENANCE, source.role());
        SnapshotDomain domain = new SnapshotDomain(
                store.loadAllWithFieldGraph(richFile).objects(), richGraph);
        assertFalse(domain.fields("State").stream()
                .anyMatch(field -> "wikidataSource".equals(field.field())),
                "inspectable provenance is not a domain-operation field");
    }

    @Test void generatedModelSubclassHierarchySurvivesTheRichSave() throws Exception {
        // Phase B relies on this: a generated model's subclass (baseClassName) must round-trip
        // through the rich save, so its baseType edge and its own field survive save -> load.
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.addField("name", FieldType.STRING, FieldCardinality.SINGLE);
        model.addClass(person);
        GeneratedClassModel director = new GeneratedClassModel("Director");
        director.baseClassName("Person");
        director.addField("credits", FieldType.NUMBER, FieldCardinality.SINGLE);
        model.addClass(director);
        model.rootClass(person);

        WikidataDynamicObject alice = wdo("Q1", "Director", false);
        alice.put("name", "Alice");
        alice.put("credits", 12L);
        WikidataDynamicObject bob = wdo("Q2", "Person", false);
        bob.put("name", "Bob");

        File file = new File(dir, "generated-subclass.snapshot.json");
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithFieldGraph(new ArrayList<>(List.of(alice, bob)), file, model);

        var loaded = store.loadAllWithFieldGraph(file);
        SnapshotDomain restored = new SnapshotDomain(loaded.objects(), loaded.fieldGraph());

        assertEquals("Person", restored.baseType("Director"),
                "the model's subclass hierarchy survives the rich save");
        assertNotNull(restored.fieldSchema("Director").field("credits"),
                "the subclass's own field survives");
        assertNull(restored.fieldSchema("Person").field("credits"),
                "the subclass field does not leak onto the base");
        assertEquals(2, restored.instancesOf("Person").size(),
                "a Director is polymorphically a Person after reload");
    }

    @Test void selfReferenceDecisionsSurviveWithoutTheDroppedObjects() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        model.rootClass(nomination);
        WikidataDynamicObject kept = wdo("Q1$a", "Nomination", false);
        var statementSource = new quiz.source.WikidataStatementSource(
                "Q1$a", "Q1", "P1411", "Q2", "Category", "preferred",
                Map.of("P805", List.of("Q3")),
                List.of(new quiz.source.WikidataStatementSource.Reference(
                        "reference-hash", Map.of("P248", List.of("Q4")))));
        kept.addWikidataStatementSource(statementSource);
        var ledger = new wikidata.explore.transform.SelfReferenceLedger(true, List.of(
                new wikidata.explore.transform.SelfReferenceLedger.Entry(
                        wikidata.explore.transform.SelfReferenceLedger.Decision.DROPPED,
                        "Nomination", "dropped", "phantom", "real", "witness",
                        List.of("category", "ceremony"), "same-slot witness",
                        statementSource, statementSource),
                new wikidata.explore.transform.SelfReferenceLedger.Entry(
                        wikidata.explore.transform.SelfReferenceLedger.Decision.SUSPECTED,
                        "Nomination", "suspect", "survivor", "", "",
                        List.of(), "survived", null, null)));
        File file = new File(dir, "self-reference-ledger.snapshot.json");

        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                List.of(kept), file, model, List.of(),
                datasource.graph.GraphDiscoveryState.EMPTY, ledger);
        var loaded = new WikidataDynamicObjectJsonStore().loadAllWithFieldGraph(file);

        assertEquals(List.of("Q1$a"), loaded.objects().stream()
                .map(WikidataDynamicObject::getIdentifier).toList());
        assertEquals(statementSource.getDisplayName(), loaded.objects().getFirst()
                .wikidataStatementSources().getFirst().getDisplayName());
        assertEquals(ledger, loaded.selfReferences(),
                "the decision about an absent object is snapshot metadata, not a field");
    }

    @Test void graphRoundTripsWithInlineValuesAndDrivesDomainWithoutInstanceScan()
            throws Exception {
        WikidataDynamicObject laureate = wdo("L1", "Laureate", false);
        laureate.put("portrait", "portrait.jpg");

        WikidataDynamicObject motivation = wdo(null, "Motivation", true);
        motivation.put("action", "promotion");
        motivation.put("topics", List.of("international law"));

        WikidataDynamicObject wrapper =
                wdo(null, "LaureatesWithMotivation", true);
        wrapper.put("laureates", List.of(laureate));
        wrapper.put("motivation", motivation);

        WikidataDynamicObject prize = wdo("P1", "NobelPrize", false);
        prize.put("laureatesWithMotivation", List.of(wrapper));

        File file = new File(dir, "nobel.snapshot.json");
        WikidataDynamicObjectJsonStore store =
                new WikidataDynamicObjectJsonStore();
        store.save(List.of(prize, laureate), file);

        WikidataDynamicObjectJsonStore.LoadedSnapshot loaded =
                store.loadAllWithFieldGraph(file);
        assertEquals(SnapshotFieldGraph.FORMAT_VERSION,
                loaded.fieldGraph().version);
        assertTrue(loaded.fieldGraph().types.get("Motivation").valueObject);
        assertFalse(loaded.fieldGraph().memberTypes().contains("Motivation"),
                "inline VALUE types are graph nodes, not selectable members");

        // Destroy the runtime values after load. The domain must still expose its
        // complete nested schema because the persisted graph is authoritative.
        loaded.objects().forEach(o -> o.dynamicFields().clear());
        SnapshotDomain domain = new SnapshotDomain(
                new ArrayList<>(loaded.objects()), loaded.fieldGraph());
        List<String> fields = domain.fields("NobelPrize").stream()
                .map(DomainField::field).toList();

        assertTrue(fields.contains(
                "laureatesWithMotivation.motivation.action"), fields.toString());
        assertTrue(fields.contains(
                "laureatesWithMotivation.laureates.portrait"), fields.toString());
    }

    @Test void preGraphSnapshotMustBeRegenerated()
            throws Exception {
        WikidataDynamicObject laureate = wdo("L1", "Laureate", false);
        laureate.put("portrait", "portrait.jpg");
        File file = new File(dir, "legacy.snapshot.json");
        WikidataDynamicObjectJsonStore store =
                new WikidataDynamicObjectJsonStore();
        store.save(List.of(laureate), file);

        ObjectNode json = (ObjectNode) store.mapper().readTree(file);
        json.remove("fieldGraph");
        json.put("version", 2);
        store.mapper().writeValue(file, json);

        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> store.loadAllWithFieldGraph(file));
        assertTrue(failure.getMessage().contains("regenerate"));
    }

    @Test void previousFlatSnapshotVersionMustBeRegenerated() throws Exception {
        File file = new File(dir, "previous.snapshot.json");
        WikidataDynamicObjectJsonStore store =
                new WikidataDynamicObjectJsonStore();
        store.save(List.of(wdo("L1", "Laureate", false)), file);

        ObjectNode json = (ObjectNode) store.mapper().readTree(file);
        json.put("version", 5);
        store.mapper().writeValue(file, json);

        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> store.loadAllWithFieldGraph(file));
        assertTrue(failure.getMessage().contains("regenerate"));
    }

    @Test void bareReferencesKeepTheirTransparentTextShape()
            throws Exception {
        WikidataDynamicObject film =
                new WikidataDynamicObject("Q11424", "film"); // deliberately unstamped
        WikidataDynamicObject work = wdo("W1", "Work", false);
        work.put("type", List.of(film));

        File file = new File(dir, "bare-reference.snapshot.json");
        WikidataDynamicObjectJsonStore store =
                new WikidataDynamicObjectJsonStore();
        store.save(List.of(work), file);

        var graph = store.loadAllWithFieldGraph(file).fieldGraph();
        SnapshotFieldGraph.FieldShape type =
                graph.types.get("Work").fields.get("type");
        assertTrue(type.collection);
        assertFalse(type.reference,
                "an unstamped label object must remain transparent to rendering");
        assertEquals("Collection<String>", type.typeLabel());
    }

    @Test void anExplicitlyRootlessCurrentSnapshotStaysRootless()
            throws Exception {
        WikidataDynamicObject group = wdo("All", "ViewableGroup", false);
        File file = new File(dir, "group-only.snapshot.json");
        WikidataDynamicObjectJsonStore store =
                new WikidataDynamicObjectJsonStore();

        store.saveWithGroupRootBindings(
                List.of(), List.of(new WikidataDynamicObjectJsonStore.GroupRootBinding(
                        "State", group)), file, null);

        var loaded = store.loadAllWithFieldGraph(file);
        assertTrue(loaded.memberRoots().isEmpty());
        assertEquals(List.of("All"), loaded.groupRoots().stream()
                .map(WikidataDynamicObject::getIdentifier).toList());
        assertEquals(List.of("State"), loaded.groupRootBindings().stream()
                .map(WikidataDynamicObjectJsonStore.LoadedGroupRoot::memberType).toList());
    }

    @Test void anUnsupportedFieldValueFailsBeforeADeadSnapshotIsWritten() {
        WikidataDynamicObject object = wdo("Q1", "Thing", false);
        object.put("opaque", new Object());
        File file = new File(dir, "unsupported.snapshot.json");

        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> new WikidataDynamicObjectJsonStore().save(List.of(object), file));

        assertTrue(failure.getMessage().contains("Unsupported snapshot field value"));
        assertFalse(file.exists(), "validation must precede opening the destination file");
    }

    private static WikidataDynamicObject wdo(
            String id, String type, boolean valueObject) {
        WikidataDynamicObject object =
                new WikidataDynamicObject(id, type);
        object.type(type);
        object.valueObject(valueObject);
        return object;
    }
}
