package wikidata.explore.extract;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.transform.app.SnapshotDomain;
import quiz.transform.ui.DomainField;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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

    @Test void preGraphSnapshotDerivesSchemaAsCompatibilityFallback()
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

        var loaded = store.loadAllWithFieldGraph(file);
        assertNotNull(loaded.fieldGraph());
        assertTrue(loaded.fieldGraph().types.get("Laureate")
                .fields.containsKey("portrait"));
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

    @Test void anExplicitlyRootlessV5SnapshotStaysRootless()
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

    private static WikidataDynamicObject wdo(
            String id, String type, boolean valueObject) {
        WikidataDynamicObject object =
                new WikidataDynamicObject(id, type);
        object.type(type);
        object.valueObject(valueObject);
        return object;
    }
}
