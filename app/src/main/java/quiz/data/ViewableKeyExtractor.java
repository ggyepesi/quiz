package quiz.data;

import objectview.Viewable;
import objectview.field.FieldAccess;
import objectview.field.FieldPath;
import objectview.field.ViewableFieldPaths;
import objectview.viewconfig.ViewConfig;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * Converts a ViewConfig into field paths and extracts the corresponding key
 * combinations from a Viewable. This is quiz-domain logic: it has no Swing,
 * HTTP, JSON, scoring, or round dependencies.
 */
public final class ViewableKeyExtractor {

    public List<FieldPath> paths(ViewConfig config) {
        return ViewableFieldPaths.collect(config, ViewableFieldPaths.ALL_FIELDS)
                .stream().map(ViewableFieldPaths.PathInfo::path).toList();
    }

    /** Configured paths for the actual backing being quizzed. Explicit saved-domain
     * paths come directly from the config; a sample is needed only for the classless
     * all-fields shorthand, which cannot name fields by itself. */
    public List<FieldPath> paths(Viewable viewable, ViewConfig config) {
        if (config == null) return List.of();
        List<FieldPath> configured = paths(config);
        if (!configured.isEmpty() || config.getCls() != null || viewable == null) {
            return configured;
        }
        return ViewableFieldPaths.collectFromSample(
                        viewable, config, ViewableFieldPaths.ALL_FIELDS)
                .stream().map(ViewableFieldPaths.PathInfo::path).toList();
    }

    public List<List<Object>> combinations(Viewable viewable, ViewConfig config) {
        if (viewable == null || config == null) {
            return List.of();
        }
        return combinations(viewable, paths(viewable, config));
    }

    public List<List<Object>> combinations(
            Viewable viewable, List<FieldPath> paths) {
        if (viewable == null || paths == null || paths.isEmpty()) {
            return List.of();
        }

        List<List<Object>> alternativesPerPath = new ArrayList<>();
        for (FieldPath path : paths) {
            List<Object> alternatives = alternatives(viewable, path);
            alternatives.removeIf(ViewableKeyExtractor::isEmptyValue);
            if (alternatives.isEmpty()) {
                return List.of();
            }
            alternativesPerPath.add(alternatives);
        }
        if (LazyCartesianKeys.product(alternativesPerPath) > Integer.MAX_VALUE) {
            Map<FieldPath, Integer> counts = new LinkedHashMap<>();
            for (int i = 0; i < paths.size(); i++) {
                counts.put(paths.get(i), alternativesPerPath.get(i).size());
            }
            throw new TooManyCombinations(viewable, counts);
        }

        return new LazyCartesianKeys(alternativesPerPath);
    }

    /** One instance's selected fields multiply past what a key list can index. Names
     *  the instance and how many values each field contributed, so the person choosing
     *  fields can see which selection to narrow. */
    public static final class TooManyCombinations extends IllegalArgumentException {
        private final transient Viewable instance;
        private final Map<FieldPath, Integer> valueCounts;

        TooManyCombinations(Viewable instance, Map<FieldPath, Integer> valueCounts) {
            super(describe(instance, valueCounts));
            this.instance = instance;
            this.valueCounts = Map.copyOf(valueCounts);
        }

        public Viewable instance() { return instance; }
        public Map<FieldPath, Integer> valueCounts() { return valueCounts; }

        private static String describe(Viewable instance, Map<FieldPath, Integer> counts) {
            String fields = counts.entrySet().stream()
                    .filter(e -> e.getValue() > 1)
                    .sorted(Map.Entry.<FieldPath, Integer>comparingByValue().reversed())
                    .map(e -> e.getKey().dotted() + " (" + e.getValue() + " values)")
                    .collect(java.util.stream.Collectors.joining(", "));
            return "The " + counts.size() + " selected fields of " + safeName(instance)
                    + " combine into more than " + Integer.MAX_VALUE
                    + " question or answer values. Multi-valued fields: " + fields
                    + ". Select fewer fields.";
        }
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
        return value(viewable, FieldPath.parse(dottedPath));
    }

    public Object value(Viewable viewable, FieldPath path) {
        if (viewable == null || path == null || path.isRoot()) {
            return null;
        }
        return FieldAccess.getPathValues(viewable, path);
    }

    public List<Object> alternatives(Viewable viewable, String dottedPath) {
        if (dottedPath == null || dottedPath.isBlank()) {
            return List.of();
        }
        return alternatives(viewable, FieldPath.parse(dottedPath));
    }

    public List<Object> alternatives(Viewable viewable, FieldPath path) {
        Object raw = value(viewable, path);
        List<Object> out = new ArrayList<>();
        flattenAlternatives(raw, out);
        return out;
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

    /** Immutable random-access view of a Cartesian product. Like a virtualized card
     * list, it retains the compact alternatives and materializes only the requested
     * combination. */
    static final class LazyCartesianKeys
            extends AbstractList<List<Object>> implements RandomAccess {
        private final List<List<Object>> alternatives;
        private final int size;

        LazyCartesianKeys(List<List<Object>> alternatives) {
            this.alternatives = List.copyOf(alternatives);
            long product = product(alternatives);
            if (product > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Selected fields produce more than " + Integer.MAX_VALUE
                                + " value combinations for one instance.");
            }
            this.size = (int) product;
        }

        /** The number of combinations, saturated just past the int limit so a product
         *  of many large fields cannot overflow a long either. */
        static long product(List<List<Object>> alternatives) {
            long product = 1;
            for (List<Object> values : alternatives) {
                product *= values.size();
                if (product > Integer.MAX_VALUE) return (long) Integer.MAX_VALUE + 1;
            }
            return product;
        }

        @Override public int size() {
            return size;
        }

        @Override public List<Object> get(int index) {
            Objects.checkIndex(index, size);
            Object[] key = new Object[alternatives.size()];
            int remaining = index;
            for (int field = alternatives.size() - 1; field >= 0; field--) {
                List<Object> values = alternatives.get(field);
                key[field] = values.get(remaining % values.size());
                remaining /= values.size();
            }
            return List.of(key);
        }
    }

    private static String safeName(Viewable viewable) {
        String name = viewable == null ? null : viewable.getName();
        return name == null ? "" : name;
    }
}
