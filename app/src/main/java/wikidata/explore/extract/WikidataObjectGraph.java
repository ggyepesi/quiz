package wikidata.explore.extract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every object reachable from a set of roots through dynamic field values.
 *
 * <p>The pool a caller holds is not the pool that gets saved: a referent can exist ONLY
 * nested inside another record — a Ceremony reached as a Nomination's qualifier value is
 * never a top-level entry — and the snapshot writer walks the whole graph. Anything that
 * wants to describe what will be saved (a field load, a counts record) has to walk the
 * same way, so the walk lives here rather than being re-implemented per caller.</p>
 */
public final class WikidataObjectGraph {

    private WikidataObjectGraph() { }

    /** Roots first, then everything they reach — breadth first, each object once. */
    public static List<WikidataDynamicObject> reachable(
            Collection<WikidataDynamicObject> roots) {
        Set<WikidataDynamicObject> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<WikidataDynamicObject> queue = new ArrayDeque<>();
        if (roots != null) {
            for (WikidataDynamicObject root : roots) {
                if (root != null && seen.add(root)) {
                    queue.addLast(root);
                }
            }
        }
        List<WikidataDynamicObject> out = new ArrayList<>(seen.size());
        while (!queue.isEmpty()) {
            WikidataDynamicObject o = queue.pollFirst();   // FIFO: preserve root order
            out.add(o);
            for (Object value : o.dynamicFieldValues().values()) {
                push(value, seen, queue);
            }
        }
        return out;
    }

    private static void push(
            Object value, Set<WikidataDynamicObject> seen,
            Deque<WikidataDynamicObject> queue) {
        if (value instanceof WikidataDynamicObject w) {
            if (seen.add(w)) {
                queue.addLast(w);
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                push(item, seen, queue);
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                push(item, seen, queue);
            }
        }
    }
}
