package quiz.transform.ui;

import quiz.Quizable;
import quiz.QuizableAdapter;
import wikidata.explore.extract.WikidataDynamicObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a Quizable graph to a {@link WikidataDynamicObject} pool so a transform
 * result can be SAVED as a snapshot (the snapshot store is WDO-based). A member
 * that is already a WDO passes through; a hand-written Quizable (State, NobelPrize,
 * …) is materialized field-by-field (reflection), its Quizable-valued fields
 * converted recursively to WDO refs (deduped by identity, cycle-safe). The result
 * is a first-class domain the web can serve.
 */
public final class QuizableToWdo {

    private QuizableToWdo() {}

    /** The root WDOs for {@code members}; referenced WDOs are reachable from them
     *  (the snapshot store follows refs when saving). */
    public static List<WikidataDynamicObject> pool(Collection<? extends Quizable> members) {
        Map<Object, WikidataDynamicObject> seen = new IdentityHashMap<>();
        List<WikidataDynamicObject> roots = new ArrayList<>();
        for (Quizable m : members) {
            Object c = convert(m, seen);
            if (c instanceof WikidataDynamicObject w) {
                roots.add(w);
            }
        }
        return roots;
    }

    private static Object convert(Object v, Map<Object, WikidataDynamicObject> seen) {
        if (v == null) {
            return null;
        }
        if (v instanceof WikidataDynamicObject w) {
            return w;   // already a snapshot object
        }
        if (v instanceof Quizable q) {
            WikidataDynamicObject cached = seen.get(q);
            if (cached != null) {
                return cached;
            }
            String id = q.getIdentifier();
            if (id == null || id.isBlank()) {
                id = q.getDisplayName();
            }
            WikidataDynamicObject o = new WikidataDynamicObject(id, q.getDisplayName());
            o.type(q.typeName());
            seen.put(q, o);
            for (Field f : QuizableAdapter.getAllFields(q.getClass())) {
                try {
                    f.setAccessible(true);
                    Object converted = convert(f.get(q), seen);
                    if (converted != null) {
                        o.put(f.getName(), converted);
                    }
                } catch (Exception ignored) {
                    // skip unreadable fields
                }
            }
            return o;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object item : c) {
                Object cv = convert(item, seen);
                if (cv != null) {
                    out.add(cv);
                }
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            List<Object> out = new ArrayList<>();
            for (Object item : m.values()) {
                Object cv = convert(item, seen);
                if (cv != null) {
                    out.add(cv);
                }
            }
            return out;
        }
        return v;   // scalar
    }
}
