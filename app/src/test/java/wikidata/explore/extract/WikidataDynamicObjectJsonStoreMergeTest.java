package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save must merge same-qid instances rather than keep the first-reached: a
 * field-poor reference copy (made outside the registry) must not overwrite the
 * rich carrier and drop its fields (e.g. a nominee's {@code type}).
 */
class WikidataDynamicObjectJsonStoreMergeTest {

    private static Object typeValue(WikidataDynamicObject o) {
        return o.get("type");
    }

    @Test
    void poorCopyDoesNotDropRichCarriersType(@TempDir Path dir) throws Exception {
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();

        // The type value (bare class stub, as `type` values are).
        WikidataDynamicObject song = new WikidataDynamicObject("Q7366", "song");

        // Rich carrier: the nominated work with its type.
        WikidataDynamicObject rich = new WikidataDynamicObject("Q1", "For Your Eyes Only");
        rich.type("OscarNominations");
        rich.merge("type", song);

        // Field-poor copy of the SAME qid, reached FIRST at save time.
        WikidataDynamicObject poor = new WikidataDynamicObject("Q1", "For Your Eyes Only");
        poor.put("wikidata", "https://www.wikidata.org/wiki/Q1");

        File file = dir.resolve("snap.json").toFile();
        store.save(List.of(poor, rich), file);

        List<WikidataDynamicObject> reloaded = store.loadAll(file);
        Map<String, WikidataDynamicObject> byQid = new java.util.HashMap<>();
        for (WikidataDynamicObject o : reloaded) byQid.put(o.qid(), o);

        WikidataDynamicObject q1 = byQid.get("Q1");
        assertNotNull(q1, "carrier must be saved");
        // Merged: keeps the real stamp, keeps the poor copy's field, and — the
        // point — keeps the rich carrier's `type` link.
        assertEquals("OscarNominations", q1.typeName());
        assertTrue(q1.dynamicFields().containsKey("wikidata"));

        Object type = typeValue(q1);
        assertNotNull(type, "type link must survive the round-trip");
        String typeQid = (type instanceof java.util.Collection<?> col)
                ? ((WikidataDynamicObject) col.iterator().next()).qid()
                : ((WikidataDynamicObject) type).qid();
        assertEquals("Q7366", typeQid);
        assertTrue(byQid.containsKey("Q7366"), "type value entity present");
    }

    @Test
    void sameIdDifferentTypesAndReferencesRemainDistinct(@TempDir Path dir) throws Exception {
        WikidataDynamicObject state = new WikidataDynamicObject("France", "France");
        state.type("State");
        state.typeKey("State");
        state.put("capital", "Paris");

        WikidataDynamicObject group = new WikidataDynamicObject("France", "France");
        group.type("ViewableGroup");
        group.typeKey("ViewableGroup");
        group.put("members", List.of("Metropolitan France"));

        // Two copies of one carrier force unionValues to compare the referenced values.
        WikidataDynamicObject first = new WikidataDynamicObject("root", "Root");
        first.type("Container");
        first.typeKey("Container");
        first.put("items", List.of(state));
        WikidataDynamicObject second = new WikidataDynamicObject("root", "Root");
        second.type("Container");
        second.typeKey("Container");
        second.put("items", List.of(group));

        File file = dir.resolve("typed.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(List.of(first, second), file);

        List<WikidataDynamicObject> loaded = store.loadAll(file);
        List<WikidataDynamicObject> france = loaded.stream()
                .filter(o -> "France".equals(o.qid()))
                .toList();
        assertEquals(2, france.size());
        assertTrue(france.stream().anyMatch(o -> "State".equals(o.typeKey())
                && "Paris".equals(o.get("capital"))));
        assertTrue(france.stream().anyMatch(o -> "ViewableGroup".equals(o.typeKey())
                && o.get("members") != null));

        WikidataDynamicObject root = loaded.stream()
                .filter(o -> "root".equals(o.qid()))
                .findFirst().orElseThrow();
        assertTrue(root.get("items") instanceof java.util.Collection<?>);
        assertEquals(2, ((java.util.Collection<?>) root.get("items")).size(),
                "typed values sharing an id must not be deduplicated");
    }
}
