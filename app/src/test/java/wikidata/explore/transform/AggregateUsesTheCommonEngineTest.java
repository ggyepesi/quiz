package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Aggregation groups through the one engine, and produces what it always produced.
 *
 * <p>It had its own LinkedHashMap loop, its own stable-key call and its own missing-key
 * enum — {@code EXCLUDE} and {@code GROUP}, which are {@code REJECT_CANDIDATE} and
 * {@code INCOMPLETE_GROUP} under other names. Two implementations of "several candidates
 * are one thing", agreeing by coincidence rather than by construction.
 *
 * <p>What stays local is CONSTRUCTION: an aggregate makes an instance of ANOTHER class at
 * a coarser grain and keeps its sources as members. That is why it remains a separate
 * step rather than becoming a kind of reduction.
 *
 * <p>Nobel is the case that would show a difference: 634 prizes grouped from 716 awards
 * by category and year.
 */
class AggregateUsesTheCommonEngineTest {

    private static int size(Object value) {
        return value instanceof Collection<?> many ? many.size() : value == null ? 0 : 1;
    }

    @Test void nobelsPrizesAreTheSameAfterGroupingMovedToTheEngine() throws Exception {
        File dir = new File("../data/wikidata/nobelprizes");
        GeneratedProjectModel model = new GeneratedProjectModelStore()
                .load(new File(dir, "nobelprizes.model.json"));
        List<WikidataDynamicObject> all = new WikidataDynamicObjectJsonStore()
                .loadAll(new File(dir, "nobelprizes.snapshot.json"));

        Map<String, WikidataDynamicObject> saved = new TreeMap<>();
        for (WikidataDynamicObject object : all) {
            if (object != null && "NobelPrize".equals(object.typeKey())) {
                saved.put(object.getIdentifier(), object);
            }
        }

        List<WikidataDynamicObject> pool = new ArrayList<>(all);
        int made = ModelAggregates.apply(ProjectModelCompiler.compile(model), pool, null);

        Map<String, WikidataDynamicObject> rebuilt = new TreeMap<>();
        for (WikidataDynamicObject object : pool) {
            if (object != null && "NobelPrize".equals(object.typeKey())) {
                rebuilt.put(object.getIdentifier(), object);
            }
        }

        assertEquals(saved.size(), made, "the same number of prizes is produced");
        assertEquals(saved.keySet(), rebuilt.keySet(),
                "and each is identified exactly as it was — the key is the key");

        List<String> differing = new ArrayList<>();
        for (var entry : saved.entrySet()) {
            int before = size(entry.getValue().get("awards"));
            int after = size(rebuilt.get(entry.getKey()).get("awards"));
            if (before != after) {
                differing.add(entry.getKey() + ": " + before + " -> " + after);
            }
        }
        assertEquals(List.of(), differing,
                "and holds the same members: the union collects what the group contains");
    }
}
