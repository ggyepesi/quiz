package quiz.facet;

import quiz.DynamicFields;
import quiz.Quizable;
import quiz.QuizableAdapter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Reflective extraction of facet keys from a field value. */
final class FacetKeys {

    private FacetKeys() {}

    static List<String> fromField(Quizable q, String fieldName) {
        if (q instanceof DynamicFields dyn && dyn.dynamicFieldValues().containsKey(fieldName)) {
            return keysOf(dyn.dynamicFieldValues().get(fieldName));
        }
        Field f = QuizableAdapter.getField(q.getClass(), fieldName);
        if (f == null) {
            return List.of();
        }
        try {
            f.setAccessible(true);
            return keysOf(f.get(q));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The {@link Quizable} value(s) of a field — for reference facets. */
    static List<Quizable> refsFromField(Quizable q, String fieldName) {
        if (q instanceof DynamicFields dyn && dyn.dynamicFieldValues().containsKey(fieldName)) {
            return refsOf(dyn.dynamicFieldValues().get(fieldName));
        }
        Field f = QuizableAdapter.getField(q.getClass(), fieldName);
        if (f == null) {
            return List.of();
        }
        try {
            f.setAccessible(true);
            return refsOf(f.get(q));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<Quizable> refsOf(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Quizable q) {
            return List.of(q);
        }
        List<Quizable> out = new ArrayList<>();
        if (value instanceof Collection<?> c) {
            for (Object item : c) {
                out.addAll(refsOf(item));
            }
        } else if (value instanceof Map<?, ?> m) {
            for (Object item : m.values()) {
                out.addAll(refsOf(item));
            }
        }
        return out;
    }

    static List<String> keysOf(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Quizable q) {
            return single(q.getDisplayName());
        }
        if (value instanceof Collection<?> c) {
            List<String> out = new ArrayList<>();
            for (Object item : c) {
                out.addAll(keysOf(item));
            }
            return out;
        }
        if (value instanceof Map<?, ?> m) {
            List<String> out = new ArrayList<>();
            for (Object item : m.values()) {
                out.addAll(keysOf(item));
            }
            return out;
        }
        return single(String.valueOf(value));
    }

    private static List<String> single(String s) {
        return s == null || s.isBlank() ? List.of() : List.of(s);
    }
}
