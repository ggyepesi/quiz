package quiz.transform;

import wikidata.explore.extract.WikidataDynamicObject;

import org.junit.jupiter.api.Test;
import objectview.field.DynamicFields;
import objectview.ViewableAdapter;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * quiz.transform running directly on {@link DynamicFields} objects — the shape a
 * saved domain snapshot has (fields in a property map, no compiled class), so the
 * view layer needs no re-materialize.
 */
class DynamicFieldsTransformTest {

    /** A snapshot-shaped object: fields live in a map, like WikidataDynamicObject. */
    public static class DynObj extends ViewableAdapter implements DynamicFields {
        public final Map<String, Object> map = new LinkedHashMap<>();
        public DynObj() {}
        DynObj(Object... kv) {
            for (int i = 0; i < kv.length; i += 2) {
                map.put((String) kv[i], kv[i + 1]);
            }
        }
        @Override public Map<String, Object> dynamicFieldValues() { return map; }
        @Override public String getIdentifier() { return String.valueOf(map.get("id")); }
        @Override public String getDisplayName() {
            return String.valueOf(map.getOrDefault("nominee", getIdentifier()));
        }
    }

    @Test void filterAndCopyReadWriteTheMap() {
        List<DynObj> noms = List.of(
                new DynObj("id", "1", "won", true,  "category", "Best Actor", "year", 1993, "nominee", "Al Pacino"),
                new DynObj("id", "2", "won", false, "category", "Best Actor", "year", 1993, "nominee", "Denzel"));

        ClassTransformPlan<DynObj, DynObj> plan =
                new ClassTransformPlan<>(DynObj.class, DynObj.class)
                        .whereFieldEquals("won", true)
                        .copy("category", "cat")
                        .copy("year", "yr")
                        .copy("nominee", "who");

        TransformContext ctx = new TransformRunner().add(plan).run(noms);
        List<DynObj> winners = ctx.targets(DynObj.class);

        assertEquals(1, winners.size(), "filter reads `won` from the map → only the winner");
        DynObj w = winners.get(0);
        assertEquals("Best Actor", w.map.get("cat"), "copy wrote into the target's map");
        assertEquals(1993, w.map.get("yr"));
        assertEquals("Al Pacino", w.map.get("who"));
    }

    @Test void invertReferenceBuildsCollectionInTheMap() {
        DynObj cat = new DynObj("id", "c1", "name", "Best Actor");
        DynObj nom = new DynObj("id", "n1", "category", cat);   // reference held in the map

        ClassTransformPlan<DynObj, DynObj> plan =
                new ClassTransformPlan<>(DynObj.class, DynObj.class)
                        .invertReference("category", DynObj.class, "nominations");

        TransformContext ctx = new TransformRunner().add(plan).run(List.of(nom));

        DynObj catTarget = ctx.getOrCreate(cat, DynObj.class);
        Object nominations = catTarget.map.get("nominations");
        assertTrue(nominations instanceof Collection<?>,
                "the inverse collection was created in the target's map");
        assertEquals(1, ((Collection<?>) nominations).size());
    }
}
