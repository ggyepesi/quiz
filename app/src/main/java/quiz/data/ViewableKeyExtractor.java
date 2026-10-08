package quiz.data;

import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.field.DynamicFields;
import objectview.field.FieldAccess;
import objectview.field.FieldPath;
import objectview.field.FieldRef;
import objectview.field.FieldSchema;
import objectview.field.FieldSet;
import objectview.plan.LiteralPaths;
import objectview.plan.TypeShape;
import objectview.plan.ViewConfigDesugar;
import objectview.viewconfig.ViewConfig;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;

/**
 * Converts a literal selection config into field paths and extracts the
 * corresponding key combinations from a Viewable. This is quiz-domain logic: it has no Swing,
 * HTTP, JSON, scoring, or round dependencies.
 */
public final class ViewableKeyExtractor {

    // The schemas a selection is rewritten against: an instance's own (its most
    // specific class) and a declared type's by name. Without them an instance's type is
    // its carried schema or its class; its values never decide what fields exist.
    private java.util.function.Function<Viewable, FieldSchema> valueSchemas = value -> null;
    private java.util.function.Function<String, FieldSchema> typeSchemas = type -> null;

    public void setSchemas(java.util.function.Function<Viewable, FieldSchema> valueSchemas,
                           java.util.function.Function<String, FieldSchema> typeSchemas) {
        this.valueSchemas = valueSchemas == null ? value -> null : valueSchemas;
        this.typeSchemas = typeSchemas == null ? type -> null : typeSchemas;
    }

    private TypeShape shape(Viewable viewable) {
        return TypeShape.of(viewable, valueSchemas.apply(viewable), typeSchemas);
    }

    /** The key paths of {@code config} against its configured class. */
    public List<FieldPath> paths(ViewConfig config) {
        if (config == null) return List.of();
        return keyPaths(config, TypeShape.ofClass(config.getCls()));
    }

    /** The key paths of {@code config} for the actual backing being quizzed: the
     *  instance's own fields, so dynamic and declared backings read alike. */
    public List<FieldPath> paths(Viewable viewable, ViewConfig config) {
        if (config == null) return List.of();
        return keyPaths(config, viewable == null
                ? TypeShape.ofClass(config.getCls()) : shape(viewable));
    }

    /** The paths a selection puts into a key: the selection's value paths, the one
     * rule search, sort and quiz keys share ({@link LiteralPaths#selection}). Selecting
     * {@code Person.spouse} alone contributes its caption, never another Person's
     * fields. */
    private static List<FieldPath> keyPaths(ViewConfig config, TypeShape shape) {
        return LiteralPaths.selection(config, shape, false).stream()
                .map(objectview.field.ViewableFieldPaths.PathInfo::path).toList();
    }

    /** {@code config} as a literal selection: shorthand is rewritten by
     * {@link ViewConfigDesugar#selection}; a config that is already literal (as every
     * field editor emits) is used as it is, so the quiz selects and renders with the
     * one config object. */
    private static ViewConfig literal(ViewConfig config, TypeShape shape) {
        // This is the boundary that knows the same field tree is a finite quiz
        // selection, not a recursive View. Keep that fact on the shared config so
        // every desktop/web renderer interprets it identically.
        return ViewConfigDesugar.preparedSelection(config, shape);
    }

    /** The assembled query/answer object for one selected key. It contains only
     * the ticked fields, while a selected collection retains all of its members.
     * Key extraction may enumerate those members and correlate their nested fields,
     * but that does not change the content being presented. The literal selection is
     * also the presentation config; Card makes its own defensive copy before applying
     * role-specific flags. */
    public KeyContent contentObject(
            Viewable owner, ViewConfig config,
            List<FieldPath> paths, List<Object> key) {
        if (owner == null || config == null || paths == null || paths.isEmpty()
                || key == null) {
            return null;
        }
        ViewConfig literal = literal(config, shape(owner));
        return new KeyContent(new SelectedTuple(this, owner, literal), literal, paths);
    }

    /** Content assembled by the quiz engine for one key, paired with the same
     * query/answer config that selected it and the paths that produced the key.
     * Every instance of one source shares the same unmodifiable path list. */
    public record KeyContent(
            Viewable object, ViewConfig viewConfig, List<FieldPath> paths) { }

    /** The configured projection of one key's source instance under a literal
     * selection: exactly its ticked fields. The source instance remains separately
     * indexed by {@code Quiz}; this object owns presentation only. Several keys from
     * one collection deliberately project the same complete selected collection. */
    private static final class SelectedTuple extends ViewableAdapter
            implements DynamicFields {
        private final ViewableKeyExtractor extractor;
        private final Viewable source;
        private final ViewConfig config;
        private final boolean displaySelected;
        private final FieldSchema schema;
        private volatile Map<String, Object> fields;

        SelectedTuple(ViewableKeyExtractor extractor, Viewable source,
                      ViewConfig config) {
            this.extractor = extractor;
            this.source = source;
            this.config = config;
            this.displaySelected = selectsDisplay(source, config);
            List<FieldRef> selectedSchema = extractor.projectSchema(source, config);
            this.schema = () -> selectedSchema;
        }

        @Override public String getIdentifier() { return source.getIdentifier(); }
        @Override public String getDisplayName() {
            return displaySelected ? source.getDisplayName() : "";
        }
        @Override public String typeName() { return source.typeName(); }
        @Override public Set<String> directClassNames() {
            return source.directClassNames();
        }
        @Override public boolean isPart() { return source.isPart(); }
        @Override public FieldSchema dynamicFieldSchema() { return schema; }

        @Override public Map<String, Object> dynamicFieldValues() {
            Map<String, Object> ready = fields;
            if (ready == null) {
                synchronized (this) {
                    ready = fields;
                    if (ready == null) {
                        ready = Map.copyOf(extractor.projectFields(
                                source, config));
                        fields = ready;
                    }
                }
            }
            return ready;
        }
    }

    private static boolean selectsDisplay(Viewable source, ViewConfig literal) {
        if (source == null || literal == null) return false;
        if (literal.getFields().containsKey(
                objectview.field.ViewableContractFieldSet.DISPLAY_KEY)) {
            return true;
        }
        FieldRef display = FieldSet.of(source).displayField();
        return display != null && literal.getFields().containsKey(display.name());
    }

    private Map<String, Object> projectFields(
            Viewable source, ViewConfig literal) {
        FieldSet fields = FieldSet.of(source);
        Map<String, Object> projected = new LinkedHashMap<>();
        for (String name : literal.getFields().keySet()) {
            FieldRef field = fields.field(name);
            if (field == null) continue;
            // A header role computed from the Viewable contract is an address onto a
            // method, not stored data: SelectedTuple supplies it through
            // getDisplayName(). Copying it into the dynamic map would make it an
            // ordinary field once a renderer overlays a domain schema. A real
            // @DisplayField is data and is copied like any other.
            if (field.role().renderedInHeader() && field instanceof FieldRef.Computed) {
                continue;
            }
            Object raw = fields.read(name);
            if (raw == null) continue;
            Object value = projectSelectedValue(raw, literal.getFieldConfig(name));
            if (!isEmptyValue(value)) projected.put(name, value);
        }
        return projected;
    }

    private List<FieldRef> projectSchema(
            Viewable source, ViewConfig literal) {
        FieldSet fields = FieldSet.of(source);
        List<FieldRef> projected = new ArrayList<>();
        for (String name : literal.getFields().keySet()) {
            FieldRef field = fields.field(name);
            if (field != null) projected.add(field);
        }
        return List.copyOf(projected);
    }

    private Object projectSelectedValue(Object value, ViewConfig childConfig) {
        if (value instanceof Collection<?> collection) {
            List<Object> selected = new ArrayList<>(collection.size());
            for (Object item : collection) {
                Object projected = projectSelectedValue(item, childConfig);
                if (projected != null) selected.add(projected);
            }
            return selected;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> selected = new LinkedHashMap<>();
            map.forEach((key, item) -> selected.put(
                    key, projectSelectedValue(item, childConfig)));
            return selected;
        }
        if (value instanceof Viewable viewable) {
            ViewConfig selected = childConfig == null
                    ? ViewConfig.leaf() : childConfig;
            return new SelectedTuple(this, viewable, selected);
        }
        return value;
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
            // One factor whose alternatives are the elements' own combinations. Keep
            // both the per-element products and their concatenation lazy: grouping is
            // a correlation rule, not permission to materialize the product again.
            List<Integer> covered = new ArrayList<>();
            node.positions(covered);
            List<List<Object>> elements = new ArrayList<>();
            long total = 0;
            for (Object element : collection) {
                List<Factor> inner = new ArrayList<>();
                if (!factorsOf(element, node, inner, owner, paths)) continue;
                List<Object> partials = mergedPartials(inner, owner, paths);
                if (total + partials.size() > Integer.MAX_VALUE) {
                    throw tooMany(owner, paths, inner);
                }
                total += partials.size();
                elements.add(partials);
            }
            if (elements.isEmpty()) return false;
            out.add(new Factor(covered, new ConcatenatedPartials(elements, (int) total)));
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

    /** One element's factor product as a lazy list of merged partials. */
    private static List<Object> mergedPartials(
            List<Factor> factors, Viewable owner, List<FieldPath> paths) {
        List<List<Object>> alternatives = new ArrayList<>(factors.size());
        for (Factor factor : factors) alternatives.add(factor.partials());
        if (LazyCartesianKeys.product(alternatives) > Integer.MAX_VALUE) {
            throw tooMany(owner, paths, factors);
        }
        return new MergedPartials(new LazyCartesianKeys(alternatives));
    }

    private static TooManyCombinations tooMany(
            Viewable owner, List<FieldPath> paths, List<Factor> factors) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Factor factor : factors) {
            counts.put(factor.label(paths), factor.partials().size());
        }
        return new TooManyCombinations(owner, paths.size(), counts);
    }

    /** Lazily merges the independently selected fields of one collection element. */
    static final class MergedPartials extends AbstractList<Object> implements RandomAccess {
        private final LazyCartesianKeys factors;

        MergedPartials(LazyCartesianKeys factors) {
            this.factors = factors;
        }

        @Override public int size() {
            return factors.size();
        }

        @Override public Object get(int index) {
            List<Object> combination = factors.get(index);
            Partial merged = null;
            for (Object part : combination) {
                merged = merged == null ? (Partial) part : merged.merge((Partial) part);
            }
            return merged;
        }
    }

    /** Random-access concatenation of the tuple products contributed by collection
     *  elements. Only the element containing the requested index is touched. */
    static final class ConcatenatedPartials
            extends AbstractList<Object> implements RandomAccess {
        private final List<List<Object>> elements;
        private final int[] ends;
        private final int size;

        ConcatenatedPartials(List<List<Object>> elements, int size) {
            this.elements = List.copyOf(elements);
            this.ends = new int[elements.size()];
            int end = 0;
            for (int i = 0; i < elements.size(); i++) {
                end += elements.get(i).size();
                ends[i] = end;
            }
            if (end != size) {
                throw new IllegalArgumentException("Concatenated size changed while building");
            }
            this.size = size;
        }

        @Override public int size() {
            return size;
        }

        @Override public Object get(int index) {
            Objects.checkIndex(index, size);
            int element = java.util.Arrays.binarySearch(ends, index + 1);
            if (element < 0) element = -element - 1;
            int start = element == 0 ? 0 : ends[element - 1];
            return elements.get(element).get(index - start);
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
