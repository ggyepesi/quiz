package wikidata.explore.transform;

import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Log-only serve-boundary check: for each field, how many distinct value-QIDs are
 * reachable from the mapped roots vs. how many survive into the served/saved pool
 * (which dedups by qid and drops demoted/pruned objects). When they differ, the
 * gap explains why a count at generation (over the mapped graph) disagrees with
 * the count after save+load (over the pool) — e.g. distinct `type` values present
 * while mapping but missing from the snapshot. Names the dropped QIDs and a
 * carrier so the cause (unsaved carrier vs. collapsed copy) is visible.
 */
public final class PoolCoverageDiagnostic {

    private PoolCoverageDiagnostic() {}

    public static void log(Collection<WikidataDynamicObject> mappedRoots,
                           Collection<WikidataDynamicObject> servedPool,
                           GenerationLog log) {
        if (log == null || mappedRoots == null || servedPool == null) {
            return;
        }
        List<WikidataDynamicObject> reachable = closure(mappedRoots);
        Set<String> servedQids = qids(servedPool);

        Map<String, Set<String>> mappedByField = fieldValueQids(reachable);
        Map<String, Set<String>> servedByField = fieldValueQids(servedPool);
        Map<String, WikidataDynamicObject> carrierByLostQid =
                carriers(reachable);

        for (Map.Entry<String, Set<String>> e : mappedByField.entrySet()) {
            String field = e.getKey();
            Set<String> mapped = e.getValue();
            Set<String> served = servedByField.getOrDefault(field, Set.of());
            Set<String> lost = new LinkedHashSet<>(mapped);
            lost.removeAll(served);
            if (lost.isEmpty()) {
                continue;
            }
            String sample = lost.stream().limit(8).map(q -> {
                WikidataDynamicObject c = carrierByLostQid.get(q);
                boolean carrierServed = c != null && servedQids.contains(c.qid());
                return q + "(via " + (c == null ? "?" : c.qid())
                        + (carrierServed ? ",carrier-served" : ",carrier-DROPPED") + ")";
            }).collect(Collectors.joining(", "));
            log.message("Coverage: field '" + field + "' — " + mapped.size()
                    + " distinct value(s) mapped, " + served.size()
                    + " served; " + lost.size() + " dropped: " + sample + "\n");
        }
    }

    private static List<WikidataDynamicObject> closure(
            Collection<WikidataDynamicObject> roots) {
        Set<WikidataDynamicObject> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<WikidataDynamicObject> queue = new ArrayDeque<>(roots);
        List<WikidataDynamicObject> out = new ArrayList<>();
        while (!queue.isEmpty()) {
            WikidataDynamicObject o = queue.poll();
            if (o == null || !seen.add(o)) {
                continue;
            }
            out.add(o);
            for (Object v : o.dynamicFields().values()) {
                for (WikidataDynamicObject w : refs(v)) {
                    if (!seen.contains(w)) {
                        queue.add(w);
                    }
                }
            }
        }
        return out;
    }

    /** field name -> distinct QIDs appearing as a value of that field. */
    private static Map<String, Set<String>> fieldValueQids(
            Collection<WikidataDynamicObject> objects) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (WikidataDynamicObject o : objects) {
            for (Map.Entry<String, Object> f : o.dynamicFields().entrySet()) {
                for (WikidataDynamicObject w : refs(f.getValue())) {
                    if (w.qid() != null) {
                        out.computeIfAbsent(f.getKey(), k -> new LinkedHashSet<>())
                                .add(w.qid());
                    }
                }
            }
        }
        return out;
    }

    /** one carrier per referenced QID (for pointing at the source of a dropped value). */
    private static Map<String, WikidataDynamicObject> carriers(
            Collection<WikidataDynamicObject> objects) {
        Map<String, WikidataDynamicObject> out = new LinkedHashMap<>();
        for (WikidataDynamicObject o : objects) {
            for (Object v : o.dynamicFields().values()) {
                for (WikidataDynamicObject w : refs(v)) {
                    if (w.qid() != null) {
                        out.putIfAbsent(w.qid(), o);
                    }
                }
            }
        }
        return out;
    }

    private static Set<String> qids(Collection<WikidataDynamicObject> objects) {
        Set<String> out = new LinkedHashSet<>();
        for (WikidataDynamicObject o : objects) {
            if (o.qid() != null) {
                out.add(o.qid());
            }
        }
        return out;
    }

    private static List<WikidataDynamicObject> refs(Object v) {
        if (v instanceof WikidataDynamicObject w) {
            return List.of(w);
        }
        if (v instanceof Collection<?> col) {
            List<WikidataDynamicObject> out = new ArrayList<>();
            for (Object x : col) {
                if (x instanceof WikidataDynamicObject w) {
                    out.add(w);
                }
            }
            return out;
        }
        return List.of();
    }
}
