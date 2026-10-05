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

        PathNode root = PathNode.of(paths);
        List<Factor> factors = new ArrayList<>();
        if (!factorsOf(viewable, root, factors, viewable, paths)) {
            return List.of();
        }
        // Independent factors in the order of the first path each covers, so selections
        // that share no collection enumerate exactly as one factor per path did.
        factors.sort(java.util.Comparator.comparingInt(Factor::firstPosition));
        List<List<Object>> alternatives = new ArrayList<>(factors.size());
        for (Factor factor : factors) alternatives.add(factor.partials());
        if (LazyCartesianKeys.product(alternatives) > Integer.MAX_VALUE) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Factor factor : factors) {
                counts.put(factor.label(paths), factor.partials().size());
            }
            throw new TooManyCombinations(viewable, paths.size(), counts);
        }
        if (factors.size() == paths.size()) {
            // Nothing is grouped: one factor per path, already in path order, so the
            // plain product of their values is the key without assembling.
            List<List<Object>> values = new ArrayList<>(factors.size());
            for (Factor factor : factors) {
                values.add(factor.partials().stream()
                        .map(partial -> ((Partial) partial).values()[0]).toList());
            }
            return new LazyCartesianKeys(values);
        }
        return new AssembledKeys(new LazyCartesianKeys(alternatives), paths.size());
    }

    /**
     * The selected paths as a tree. Paths below the same collection are evaluated per
     * element of it, so one office's position is combined with that office's start
     * date and never with another's (#345). Paths below a single value — the instance
     * itself, or a single referenced object — stay independent factors of a lazy
     * product, as before.
     */
    private static final class PathNode {
        final Map<String, PathNode> children = new LinkedHashMap<>();
        final List<Integer> ends = new ArrayList<>();   // selected paths ending here

        static PathNode of(List<FieldPath> paths) {
            PathNode root = new PathNode();
            for (int i = 0; i < paths.size(); i++) {
                PathNode node = root;
                for (String segment : paths.get(i).segments()) {
                    node = node.children.computeIfAbsent(segment, ignored -> new PathNode());
                }
                node.ends.add(i);
            }
            return root;
        }

        void positions(List<Integer> out) {
            out.addAll(ends);
            for (PathNode child : children.values()) child.positions(out);
        }
    }

    /** Values for some of the key's positions, taken together from one element. */
    private record Partial(int[] positions, Object[] values) {
        /** One value filling every position whose path ends at the same node — the
         *  same path selected twice reads the same value twice, as it always did. */
        static Partial of(List<Integer> positions, Object value) {
            Object[] values = new Object[positions.size()];
            java.util.Arrays.fill(values, value);
            return new Partial(positions.stream().mapToInt(Integer::intValue).toArray(),
                    values);
        }

        Partial merge(Partial other) {
            int[] p = java.util.Arrays.copyOf(positions, positions.length + other.positions.length);
            Object[] v = java.util.Arrays.copyOf(values, values.length + other.values.length);
            System.arraycopy(other.positions, 0, p, positions.length, other.positions.length);
            System.arraycopy(other.values, 0, v, values.length, other.values.length);
            return new Partial(p, v);
        }
    }

    /** One independent factor of the key: the alternatives for the positions it covers. */
    private record Factor(List<Integer> covered, List<Object> partials) {
        int firstPosition() {
            return covered.stream().mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE);
        }

        String label(List<FieldPath> paths) {
            return covered.stream().sorted().map(i -> paths.get(i).dotted())
                    .collect(java.util.stream.Collectors.joining(" + "));
        }
    }

    /**
     * Adds the factors {@code node} contributes for {@code value}; false when one of its
     * selected paths has no value, so this scope yields no key at all.
     */
    private boolean factorsOf(Object value, PathNode node, List<Factor> out, Viewable owner,
                              List<FieldPath> paths) {
        if (value instanceof Collection<?> collection && !node.children.isEmpty()) {
            // One factor whose alternatives are the elements' own combinations, joined.
            List<Integer> covered = new ArrayList<>();
            node.positions(covered);
            List<Object> partials = new ArrayList<>();
            for (Object element : collection) {
                List<Factor> inner = new ArrayList<>();
                if (!factorsOf(element, node, inner, owner, paths)) continue;
                expand(inner, partials, owner, paths);
            }
            if (partials.isEmpty()) return false;
            out.add(new Factor(covered, partials));
            return true;
        }
        if (!node.ends.isEmpty()) {
            List<Object> alternatives = new ArrayList<>();
            flattenAlternatives(value, alternatives);
            alternatives.removeIf(ViewableKeyExtractor::isEmptyValue);
            if (alternatives.isEmpty()) return false;
            List<Object> partials = new ArrayList<>(alternatives.size());
            for (Object alternative : alternatives) {
                partials.add(Partial.of(node.ends, alternative));
            }
            out.add(new Factor(List.copyOf(node.ends), partials));
        }
        for (Map.Entry<String, PathNode> child : node.children.entrySet()) {
            Object next = value == null ? null
                    : FieldAccess.getPathValues(value, FieldPath.of(child.getKey()));
            if (!factorsOf(next, child.getValue(), out, owner, paths)) return false;
        }
        return true;
    }

    /** Appends every combination of {@code factors} — one element's — as one partial. */
    private static void expand(List<Factor> factors, List<Object> out, Viewable owner,
                               List<FieldPath> paths) {
        List<List<Object>> alternatives = new ArrayList<>(factors.size());
        for (Factor factor : factors) alternatives.add(factor.partials());
        long count = LazyCartesianKeys.product(alternatives);
        if (count > Integer.MAX_VALUE || out.size() + count > Integer.MAX_VALUE) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Factor factor : factors) {
                counts.put(factor.label(paths), factor.partials().size());
            }
            throw new TooManyCombinations(owner, paths.size(), counts);
        }
        for (List<Object> combination : new LazyCartesianKeys(alternatives)) {
            Partial merged = null;
            for (Object part : combination) {
                merged = merged == null ? (Partial) part : merged.merge((Partial) part);
            }
            out.add(merged);
        }
    }

    /** The lazy product of factors, each key laid out in selected-path order. */
    private static final class AssembledKeys
            extends AbstractList<List<Object>> implements RandomAccess {
        private final LazyCartesianKeys factors;
        private final int width;

        AssembledKeys(LazyCartesianKeys factors, int width) {
            this.factors = factors;
            this.width = width;
        }

        @Override public int size() {
            return factors.size();
        }

        @Override public List<Object> get(int index) {
            Object[] key = new Object[width];
            for (Object part : factors.get(index)) {
                Partial partial = (Partial) part;
                for (int i = 0; i < partial.positions().length; i++) {
                    key[partial.positions()[i]] = partial.values()[i];
                }
            }
            return List.of(key);
        }
    }

    /** One instance's selected fields multiply past what a key list can index. Names
     *  the instance and how many values each field contributed, so the person choosing
     *  fields can see which selection to narrow. */
    public static final class TooManyCombinations extends IllegalArgumentException {
        private final transient Viewable instance;
        private final Map<String, Integer> valueCounts;

        TooManyCombinations(Viewable instance, int fields, Map<String, Integer> valueCounts) {
            super(describe(instance, fields, valueCounts));
            this.instance = instance;
            this.valueCounts = Map.copyOf(valueCounts);
        }

        public Viewable instance() { return instance; }
        /** Values per independent factor: one path, or the paths kept together under
         *  one collection, joined with " + ". */
        public Map<String, Integer> valueCounts() { return valueCounts; }

        private static String describe(Viewable instance, int selected,
                                       Map<String, Integer> counts) {
            String fields = counts.entrySet().stream()
                    .filter(e -> e.getValue() > 1)
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .map(e -> e.getKey() + " (" + e.getValue() + " values)")
                    .collect(java.util.stream.Collectors.joining(", "));
            return "The " + selected + " selected fields of " + safeName(instance)
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
