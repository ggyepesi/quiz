package wikidata.explore.transform;

import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Graph-preserving deep copy of a pool of {@link WikidataDynamicObject}s. The
 * transforms (reify/dedup/canonicalize/…) mutate objects in place — promoting
 * statements, stamping types, filling role fields — so re-running them (a Remap
 * that re-transforms the cached download without re-fetching) needs a fresh,
 * independent copy each time. Cross-references are rewired to the copies and
 * cycles are handled via an identity map.
 */
public final class PoolCopy {

    private PoolCopy() {}

    public static List<WikidataDynamicObject> deepCopy(
            Collection<WikidataDynamicObject> pool) {

        Map<WikidataDynamicObject, WikidataDynamicObject> clones =
                new IdentityHashMap<>();
        Deque<WikidataDynamicObject> work = new ArrayDeque<>();

        // Seed clones for every pooled object first, so the returned list keeps
        // the same membership even for objects only reachable as references.
        for (WikidataDynamicObject o : pool) {
            if (o != null) {
                cloneShallow(o, clones, work);
            }
        }

        // Copy each object's fields, translating references to their clones and
        // discovering referenced-but-unpooled objects along the way.
        while (!work.isEmpty()) {
            WikidataDynamicObject src = work.poll();
            WikidataDynamicObject dst = clones.get(src);
            for (Map.Entry<String, Object> e : src.dynamicFields().entrySet()) {
                dst.dynamicFields().put(e.getKey(), translate(e.getValue(), clones, work));
            }
        }

        List<WikidataDynamicObject> out = new ArrayList<>(pool.size());
        for (WikidataDynamicObject o : pool) {
            out.add(o == null ? null : clones.get(o));
        }
        return out;
    }

    private static WikidataDynamicObject cloneShallow(
            WikidataDynamicObject o,
            Map<WikidataDynamicObject, WikidataDynamicObject> clones,
            Deque<WikidataDynamicObject> work) {

        WikidataDynamicObject c = clones.get(o);
        if (c != null) {
            return c;
        }
        // What makes an object itself is the object's own question. Enumerating it here
        // copied what this file knew about on the day it was written, and quietly lost
        // every piece of state added afterwards.
        c = o.copyWithoutFields();
        clones.put(o, c);
        work.add(o);   // its fields still need copying
        return c;
    }

    private static Object translate(
            Object v,
            Map<WikidataDynamicObject, WikidataDynamicObject> clones,
            Deque<WikidataDynamicObject> work) {

        if (v instanceof WikidataDynamicObject w) {
            return cloneShallow(w, clones, work);
        }
        if (v instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(translate(item, clones, work));
            }
            return copy;
        }
        if (v instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(e.getKey(), translate(e.getValue(), clones, work));
            }
            return copy;
        }
        return v;   // immutable scalar (String, Number, Boolean, ImageRef, …)
    }
}
