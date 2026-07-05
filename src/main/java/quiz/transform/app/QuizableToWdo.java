package quiz.transform.app;

import quiz.DynamicFields;
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
 * result can be SAVED as a snapshot (the store is WDO-based). A WDO passes through;
 * a {@link DynamicFields} object (e.g. a PROJECT-derived DynamicQuizable) copies its
 * property map; a hand-written Quizable (State, NobelPrize, …) is materialized by
 * reflection. Quizable-valued fields are converted recursively (deduped by
 * identity, cycle-safe). The wikidata bridge for the transform layer.
 */
public final class QuizableToWdo {

    private QuizableToWdo() {}

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
            return w;
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
            if (q instanceof DynamicFields dyn) {
                for (Map.Entry<String, Object> e : dyn.dynamicFieldValues().entrySet()) {
                    Object cv = convert(e.getValue(), seen);
                    if (cv != null) {
                        o.put(e.getKey(), cv);
                    }
                }
            } else {
                for (Field f : QuizableAdapter.getAllFields(q.getClass())) {
                    try {
                        f.setAccessible(true);
                        Object cv = convert(f.get(q), seen);
                        if (cv != null) {
                            o.put(f.getName(), cv);
                        }
                    } catch (Exception ignored) {
                        // skip unreadable fields
                    }
                }
            }
            return o;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object item : c) {
                Object cv = convert(item, seen);
                if (cv != null) out.add(cv);
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            List<Object> out = new ArrayList<>();
            for (Object item : m.values()) {
                Object cv = convert(item, seen);
                if (cv != null) out.add(cv);
            }
            return out;
        }
        return v;
    }
}
