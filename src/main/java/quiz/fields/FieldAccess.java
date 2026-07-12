package quiz.fields;

import quiz.DynamicFields;
import quiz.QuizableAdapter;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Reads/writes fields by dotted path. DynamicFields-aware: for a
 * {@link DynamicFields} object (e.g. a saved snapshot's {@code
 * WikidataDynamicObject}) the map entries ARE the fields, so the transforms run
 * on the raw snapshot without recompiling the generated classes. Declared Java
 * fields are used otherwise (typed instances).
 */
public final class FieldAccess {

    private FieldAccess() {}

    public static Object getPath(Object root, String path) {
        Object current = root;
        for (String part : split(path)) {
            if (current == null) {
                return null;
            }
            // A collection/map intermediate (e.g. languages.name): descend into a
            // representative element so a nested path resolves instead of crashing.
            if (current instanceof Collection<?> c) {
                current = c.isEmpty() ? null : c.iterator().next();
            } else if (current instanceof Map<?, ?> m) {
                current = m.isEmpty() ? null : m.values().iterator().next();
            }
            if (current == null) {
                return null;
            }
            current = readField(current, part);
        }
        return current;
    }

    public static void setPath(Object root, String path, Object value) {
        writeField(owner(root, path), split(path).getLast(), value);
    }

    public static void addToCollection(Object root, String fieldName, Object value) {
        if (root == null || value == null) {
            return;
        }
        Object current = readField(root, fieldName);

        Collection<Object> collection;
        if (current == null) {
            collection = new ArrayList<>();
            writeField(root, fieldName, collection);
        } else if (current instanceof Collection<?> c) {
            @SuppressWarnings("unchecked")
            Collection<Object> cast = (Collection<Object>) c;
            collection = cast;
        } else {
            throw new IllegalStateException(
                    "Field is not a collection: "
                            + root.getClass().getSimpleName() + "." + fieldName);
        }

        if (!collection.contains(value)) {
            collection.add(value);
        }
    }

    // --- field access (DynamicFields map first, then declared Java fields) ---

    private static Object readField(Object obj, String name) {
        // A type that publishes its own addressable views (e.g. FlexibleDate:
        // year/month/day/monthDay) owns that vocabulary — resolve through the type,
        // not reflection, so precision-aware views are authoritative. Stays
        // type-agnostic: no instanceof of any concrete value type.
        if (obj instanceof aux.Addressable a && a.viewNames().contains(name)) {
            return a.view(name);
        }
        if (obj instanceof DynamicFields dyn
                && dyn.dynamicFieldValues().containsKey(name)) {
            return dyn.dynamicFieldValues().get(name);
        }
        Field f = QuizableAdapter.getField(obj.getClass(), name);
        if (f != null) {
            try {
                f.setAccessible(true);
                return f.get(obj);
            } catch (Exception e) {
                throw new RuntimeException("Cannot read " + name + " from " + obj, e);
            }
        }
        // Identity / display come from the Quizable contract, not a raw field: for a
        // dynamic object `name`/`qid` aren't in the property map (they're identity,
        // @NotQuizableField), so getDisplayName()/getIdentifier() are the right source.
        if (obj instanceof quiz.Quizable q) {
            if ("name".equals(name)) {
                return q.getDisplayName();
            }
            if ("qid".equals(name) || "id".equals(name) || "identifier".equals(name)) {
                return q.getIdentifier();
            }
        }
        // The field isn't present (a dynamic object without it, a heterogeneous
        // nested value, a JDK type, …). Reading is tolerant — return null rather
        // than crash the caller enumerating/inspecting arbitrary domains.
        return null;
    }

    private static void writeField(Object obj, String name, Object value) {
        // A declared Java field wins if present; otherwise a dynamic object stores
        // it in its property map (so projected view fields need no compiled class).
        Field f = QuizableAdapter.getField(obj.getClass(), name);
        if (f != null) {
            try {
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (Exception e) {
                throw new RuntimeException("Cannot set " + name + " on " + obj, e);
            }
        }
        if (obj instanceof DynamicFields dyn) {
            dyn.dynamicFieldValues().put(name, value);
            return;
        }
        throw new IllegalArgumentException(
                "No field " + obj.getClass().getName() + "." + name);
    }

    private static Object owner(Object root, String path) {
        List<String> parts = split(path);
        Object current = root;
        for (int i = 0; i < parts.size() - 1; i++) {
            if (current == null) {
                throw new IllegalStateException("Null owner while resolving " + path);
            }
            current = readField(current, parts.get(i));
        }
        return current;
    }

    private static List<String> split(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Empty field path");
        }
        return Arrays.asList(path.split("\\."));
    }
}
