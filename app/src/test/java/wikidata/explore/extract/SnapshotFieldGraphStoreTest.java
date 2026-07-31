package wikidata.explore.extract;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.transform.app.SnapshotDomain;
import quiz.transform.ui.DomainField;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotFieldGraphStoreTest {

    @TempDir File dir;

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
