package wikidata.explore.transform;

import wikidata.explore.extract.WikidataDynamicObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Debug trace at the save boundary: the distinct entity VALUES of one field
 * across a pool of {@link WikidataDynamicObject}s, written as sorted
 * {@code qid\tname} lines. Call it on the same pool in two apps (fresh generation
 * vs. loaded snapshot) and {@code comm} the outputs to see, at the pool level
 * (below all rendering), whether a field's value set survives save/load — e.g.
 * whether the {@code type} dimension really is 39 in the saved pool or only 32.
 */
public final class FieldValueDump {

    private FieldValueDump() {}

    /** field name -> distinct entity values (qid -> displayName). */
    public static Map<String, String> distinctValues(
            Collection<WikidataDynamicObject> pool, String field) {
        Map<String, String> byQid = new LinkedHashMap<>();
        for (WikidataDynamicObject o : pool) {
            if (o != null) {
                collect(o.get(field), byQid);
            }
        }
        return byQid;
    }

    /** Log the values present in {@code before} but not {@code after} — the ones
     *  a save/load round-trip dropped. */
    public static void dumpLost(Collection<WikidataDynamicObject> before,
                                Collection<WikidataDynamicObject> after,
                                String field,
                                java.util.function.Consumer<String> log) {
        Map<String, String> b = distinctValues(before, field);
        Map<String, String> a = distinctValues(after, field);

        Map<String, String> afterByQid = new LinkedHashMap<>();
        for (WikidataDynamicObject o : after) {
            if (o != null && o.qid() != null) {
                afterByQid.put(o.qid(), o.getDisplayName());
            }
        }

        java.util.List<String> lost = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : b.entrySet()) {
            String q = e.getKey();
            if (a.containsKey(q)) {
                continue;
            }
            // Find a carrier of this value in `before`, and whether the value
            // and the carrier survived into `after` (by qid), and how the value
            // is held (scalar vs list) on the carrier.
            String carrierQid = "?";
            String held = "?";
            for (WikidataDynamicObject o : before) {
                if (o == null) continue;
                Object tv = o.get(field);
                if (holdsQid(tv, q)) {
                    carrierQid = o.qid();
                    held = (tv instanceof Collection<?>) ? "list" : "scalar";
                    break;
                }
            }
            boolean valueSaved = afterByQid.containsKey(q);
            boolean carrierSaved = afterByQid.containsKey(carrierQid);
            lost.add(q + " (" + e.getValue() + ") [held=" + held
                    + ", carrier=" + carrierQid
                    + (carrierSaved ? ",carrier-SAVED" : ",carrier-DROPPED")
                    + (valueSaved ? ",value-SAVED" : ",value-DROPPED") + "]");
        }
        log.accept("[field-lost] '" + field + "' before=" + b.size()
                + " after=" + a.size() + " lost=" + lost.size()
                + ": " + String.join("; ", lost));
    }

    public static void dump(Collection<WikidataDynamicObject> pool,
                            String field, File out) throws IOException {
        Map<String, String> byQid = distinctValues(pool, field);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : byQid.entrySet()) {
            lines.add(e.getKey() + "\t" + e.getValue());
        }
        lines.sort(String::compareTo);
        Files.write(out.toPath(), lines);
        System.out.println("[field-dump] " + lines.size() + " distinct '" + field
                + "' value(s) -> " + out.getAbsolutePath());
    }

    private static boolean holdsQid(Object v, String qid) {
        if (v instanceof WikidataDynamicObject w) {
            return qid.equals(w.qid());
        }
        if (v instanceof Collection<?> col) {
            for (Object x : col) {
                if (holdsQid(x, qid)) return true;
            }
        }
        return false;
    }

    private static void collect(Object v, Map<String, String> byQid) {
        if (v instanceof WikidataDynamicObject w) {
            if (w.qid() != null) {
                byQid.putIfAbsent(w.qid(), w.getDisplayName());
            }
        } else if (v instanceof Collection<?> col) {
            for (Object x : col) {
                collect(x, byQid);
            }
        }
    }
}
