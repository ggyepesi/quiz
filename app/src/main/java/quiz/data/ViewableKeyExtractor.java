package quiz.data;

import objectview.Viewable;
import objectview.field.FieldSet;
import objectview.viewconfig.ViewConfig;
import objectview.Viewable;
import objectview.ViewableAdapter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a ViewConfig into field paths and extracts the corresponding key
 * combinations from a Viewable. This is quiz-domain logic: it has no Swing,
 * HTTP, JSON, scoring, or round dependencies.
 */
public final class ViewableKeyExtractor {

    public List<List<String>> paths(ViewConfig config) {
        List<List<String>> paths = new ArrayList<>();
        collectPaths(config, new ArrayList<>(), paths);
        return List.copyOf(paths);
    }

    public List<List<Object>> combinations(Viewable viewable, ViewConfig config) {
        return combinations(viewable, paths(config));
    }

    public List<List<Object>> combinations(
            Viewable viewable, List<List<String>> paths) {
        if (viewable == null || paths == null || paths.isEmpty()) {
            return List.of();
        }

        List<List<Object>> alternativesPerPath = new ArrayList<>();
        for (List<String> path : paths) {
            List<Object> alternatives = alternatives(viewable, path);
            alternatives.removeIf(ViewableKeyExtractor::isEmptyValue);
            if (alternatives.isEmpty()) {
                return List.of();
            }
            alternativesPerPath.add(alternatives);
        }

        List<List<Object>> out = new ArrayList<>();
        buildCartesianKeys(alternativesPerPath, 0, new ArrayList<>(), out);
        return List.copyOf(out);
    }

    /**
     * Raw value at a dotted path. Collections and maps are preserved and fanned
     * through subsequent path segments; use {@link #alternatives} when one
     * scalar alternative per collection member is desired.
     */
    public Object value(Viewable viewable, String dottedPath) {
        if (dottedPath == null || dottedPath.isBlank()) {
            return null;
        }
        return value(viewable, List.of(dottedPath.split("\\.")));
    }

    public Object value(Viewable viewable, List<String> path) {
        if (viewable == null || path == null || path.isEmpty()) {
            return null;
        }
        return extractPathValueRecursive(
                viewable, path, 0,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    public List<Object> alternatives(Viewable viewable, String dottedPath) {
        if (dottedPath == null || dottedPath.isBlank()) {
            return List.of();
        }
        return alternatives(viewable, List.of(dottedPath.split("\\.")));
    }

    public List<Object> alternatives(Viewable viewable, List<String> path) {
        Object raw = value(viewable, path);
        List<Object> out = new ArrayList<>();
        flattenAlternatives(raw, out);
        return out;
    }

    private static void collectPaths(
            ViewConfig config, List<String> prefix, List<List<String>> out) {
        if (config == null) {
            return;
        }
        if (config.isAllFields()) {
            collectAllFieldPaths(config.getCls(), prefix, out);
            return;
        }

        for (Map.Entry<String, ViewConfig> entry : config.getFields().entrySet()) {
            List<String> path = new ArrayList<>(prefix);
            path.add(entry.getKey());
            ViewConfig child = entry.getValue();
            if (child == null || child.isAllFields() || child.getFields().isEmpty()) {
                out.add(List.copyOf(path));
            } else {
                collectPaths(child, path, out);
            }
        }
    }

    private static void collectAllFieldPaths(
            Class<? extends Viewable> cls,
            List<String> prefix,
            List<List<String>> out) {
        if (cls == null) {
            return;
        }
        for (Field field : ViewableAdapter.getAllFields(cls)) {
            List<String> path = new ArrayList<>(prefix);
            path.add(field.getName());
            out.add(List.copyOf(path));
        }
    }

    private Object extractPathValueRecursive(
            Object current, List<String> path, int index, Set<Object> visited) {
        if (current == null) {
            return null;
        }
        if (index >= path.size()) {
            return current;
        }

        if (current instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>();
            for (Object item : collection) {
                Object extracted = extractPathValueRecursive(item, path, index, visited);
                Object summarized = summarizeExtracted(extracted);
                if (!isEmptyValue(summarized)) {
                    out.add(summarized);
                }
            }
            return out;
        }

        if (current instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object extracted = extractPathValueRecursive(
                        entry.getValue(), path, index, visited);
                Object summarized = summarizeExtracted(extracted);
                if (!isEmptyValue(summarized)) {
                    out.put(summarizeSimple(entry.getKey()), summarized);
                }
            }
            return out;
        }

        if (current instanceof Viewable viewable) {
            if (!visited.add(viewable)) {
                return safeName(viewable);
            }
            try {
                String part = path.get(index);
                FieldSet fields = FieldSet.of(viewable);
                Object next = fields.has(part) ? fields.read(part) : null;
                return extractPathValueRecursive(next, path, index + 1, visited);
            } finally {
                visited.remove(viewable);
            }
        }

        if (current instanceof objectview.utils.Addressable addressable) {
            String part = path.get(index);
            Object next = addressable.viewNames().contains(part)
                    ? addressable.view(part) : null;
            return extractPathValueRecursive(next, path, index + 1, visited);
        }

        Field field = ViewableAdapter.getField(current.getClass(), path.get(index));
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return extractPathValueRecursive(
                    field.get(current), path, index + 1, visited);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private void flattenAlternatives(Object value, List<Object> out) {
        Object summarized = summarizeExtracted(value);
        if (isEmptyValue(summarized)) {
            return;
        }
        if (summarized instanceof Collection<?> collection) {
            for (Object item : collection) {
                flattenAlternatives(item, out);
            }
            return;
        }
        if (summarized instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = summarizeSimple(entry.getKey());
                Object val = summarizeExtracted(entry.getValue());
                if (!isEmptyValue(val)) {
                    out.add(key + " -> " + val);
                }
            }
            return;
        }
        out.add(summarized);
    }

    private Object summarizeExtracted(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Viewable viewable) {
            return safeName(viewable);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>();
            for (Object item : collection) {
                Object summarized = summarizeExtracted(item);
                if (!isEmptyValue(summarized)) {
                    out.add(summarized);
                }
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object summarized = summarizeExtracted(entry.getValue());
                if (!isEmptyValue(summarized)) {
                    out.put(summarizeSimple(entry.getKey()), summarized);
                }
            }
            return out;
        }
        return value;
    }

    private Object summarizeSimple(Object value) {
        return value instanceof Viewable viewable ? safeName(viewable) : value;
    }

    private static boolean isEmptyValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence chars) {
            return chars.toString().trim().isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty()
                    || collection.stream().allMatch(ViewableKeyExtractor::isEmptyValue);
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty()
                    || map.values().stream().allMatch(ViewableKeyExtractor::isEmptyValue);
        }
        return false;
    }

    private static void buildCartesianKeys(
            List<List<Object>> lists,
            int index,
            List<Object> current,
            List<List<Object>> out) {
        if (index == lists.size()) {
            out.add(List.copyOf(current));
            return;
        }
        for (Object value : lists.get(index)) {
            current.add(value);
            buildCartesianKeys(lists, index + 1, current, out);
            current.removeLast();
        }
    }

    private static String safeName(Viewable viewable) {
        String name = viewable == null ? null : viewable.getName();
        return name == null ? "" : name;
    }
}
