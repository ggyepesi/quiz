package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldSet;
import objectview.field.FieldPath;
import objectview.viewconfig.FieldRow;
import objectview.viewconfig.FieldTableContributor;
import quiz.curation.ScopeFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The shared field-coverage plugin: a single-select field picker that adds
 * {@code Coverage} / {@code Present} / {@code Missing} columns computed over a
 * working set of instances. One implementation for every field picker — the
 * Curate/validation coverage table AND the filter picker — so they cannot drift.
 *
 * <p>Parameterised by the domain, a supplier of the base type being shown, and a
 * supplier of the working-set instances (so the columns recompute as the selection
 * changes). {@code @subtype:X} path segments scope a subclass field to its own type.
 */
public final class FieldCoverageColumns implements FieldTableContributor {

    /** The owning type + plain field path a (possibly {@code @subtype:}-scoped) path
     *  resolves to. */
    public record Scoped(String type, FieldPath path) { }

    /** One consistent coverage calculation shared by every column and by the explicit
     *  All / Missing / Present controls beside the table. */
    public record Coverage(int eligible, int present, int unnamed) {
        public Coverage(int eligible, int present) {
            this(eligible, present, 0);
        }

        public int missing() {
            return eligible - present;
        }

        public String percentage() {
            return eligible == 0
                    ? "—"
                    : (Math.round(1000.0 * present / eligible) / 10.0) + "%";
        }
    }

    private final DomainModel domain;
    private final Supplier<String> baseType;
    private final Supplier<? extends Collection<? extends Viewable>> instances;
    // JTable asks for the same cell values repeatedly while painting. Cache one complete
    // calculation per path for the current immutable working-set list instead of scanning
    // every member separately for Coverage, Present and Missing on every repaint.
    private final Map<FieldPath, Coverage> cache = new LinkedHashMap<>();
    private String cachedBaseType;
    private Collection<? extends Viewable> cachedMembers;

    public FieldCoverageColumns(
            DomainModel domain,
            Supplier<String> baseType,
            Supplier<? extends Collection<? extends Viewable>> instances) {
        this.domain = domain;
        this.baseType = baseType == null ? () -> null : baseType;
        this.instances = instances == null ? List::of : instances;
    }

    @Override public SelectionMode selectionMode() {
        return SelectionMode.SINGLE;
    }

    @Override public List<ExtraColumn> columns() {
        return List.of(
                column("Coverage", 80, p -> coverage(p).percentage()),
                column("Present", 64, p -> String.valueOf(coverage(p).present())),
                column("Missing", 64, p -> String.valueOf(coverage(p).missing())),
                // Which fields hold references that render as a bare QID. Present, so
                // invisible in every other column — you would otherwise have to drill
                // each field in turn to discover that composer has 700 of them.
                // Always a number, never blank: a blank cell cannot be told from a
                // column that is not computing at all, which is exactly how a genuine
                // zero and a broken count came to look the same.
                column("Unnamed", 72, p -> String.valueOf(coverage(p).unnamed())));
    }

    /** The working set, never null — a supplier may return null before the first render. */
    private Collection<? extends Viewable> members() {
        Collection<? extends Viewable> set = instances.get();
        return set == null ? List.of() : set;
    }

    /** Drop cached values after in-place curation. A changed working-set collection or base
     *  type is detected automatically; this method covers mutation of the same instances. */
    public void invalidate() {
        cachedMembers = null;
        cachedBaseType = null;
        cache.clear();
    }

    public Coverage coverage(FieldPath path) {
        String currentType = baseType.get();
        Collection<? extends Viewable> currentMembers = members();
        if (currentMembers != cachedMembers || !Objects.equals(currentType, cachedBaseType)) {
            cachedMembers = currentMembers;
            cachedBaseType = currentType;
            cache.clear();
        }
        return cache.computeIfAbsent(path, p -> calculate(currentType, currentMembers, p));
    }

    private Coverage calculate(
            String currentType,
            Collection<? extends Viewable> currentMembers,
            FieldPath path) {
        Scoped scoped = scoped(currentType, path);
        if (scoped == null || domain == null) {
            return new Coverage(0, 0);
        }
        int eligible = 0;
        int present = 0;
        int unnamed = 0;
        for (Viewable q : currentMembers) {
            if (domain.isInstanceOf(q, scoped.type())) {
                eligible++;
                if (hasValue(q, scoped.path())) {
                    present++;
                    if (hasUnnamedReference(q, scoped.path())) {
                        unnamed++;
                    }
                }
            }
        }
        return new Coverage(eligible, present, unnamed);
    }

    /** Resolve a picker path (which may carry {@code @subtype:X} segments) to its owning
     *  type + plain field path, defaulting to {@code baseType}. */
    public static Scoped scoped(String baseType, FieldPath rawPath) {
        objectview.viewconfig.ViewConfigEditor.ResolvedFieldPath resolved =
                objectview.viewconfig.ViewConfigEditor.resolveFieldPath(baseType, rawPath);
        return resolved == null ? null : new Scoped(resolved.owner(), resolved.path());
    }

    /** Whether {@code q} has a non-empty value at the dotted {@code path} (descending
     *  through collection intermediates). */
    public static boolean hasValue(Viewable q, FieldPath path) {
        for (Object leaf : leaves(q, path)) {
            if (leaf == null) {
                continue;
            }
            if (leaf instanceof String s && s.isBlank()) {
                continue;
            }
            if (leaf instanceof Collection<?> c && c.isEmpty()) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Whether any value at {@code path} is a reference whose target has no name — it
     * still displays its own identifier.
     *
     * <p>Shares one traversal with {@link #hasValue}: two questions about the same
     * leaves, so a path that resolves one way for coverage cannot resolve another way
     * here.
     */
    public static boolean hasUnnamedReference(Viewable q, FieldPath path) {
        for (Object leaf : leaves(q, path)) {
            if (leaf instanceof Viewable target && isUnnamed(target)) {
                return true;
            }
        }
        return false;
    }

    /** What the source said about this field's emptiness, or null when it said nothing.
     *  Only a Wikidata-extracted instance carries one; anything else is an ordinary gap. */
    static wikidata.explore.extract.FieldStatus assertedStatus(Viewable q, FieldPath path) {
        if (!(q instanceof wikidata.explore.extract.WikidataDynamicObject wdo)
                || path == null || path.segments().size() != 1) {
            return null;
        }
        return wdo.fieldStatus(path.segments().get(0));
    }

    /** A target showing its identifier (or nothing) where its name belongs. That is
     *  what an unresolved label looks like once it reaches a card. */
    private static boolean isUnnamed(Viewable target) {
        String name = target.getDisplayName();
        if (name == null || name.isBlank()) {
            return true;
        }
        String id = target.getIdentifier();
        return id != null && !id.isBlank() && name.equals(id);
    }

    /** The values a field path resolves to — the same walk every field-scope question
     *  uses, so a caller inspecting the targets sees exactly what coverage counted. */
    public static List<Object> leafValues(Viewable q, FieldPath path) {
        return leaves(q, path);
    }

    /** The values a field path resolves to, with collections, maps and arrays fanned
     *  out — the shared walk behind every field-scope question. */
    private static List<Object> leaves(Viewable q, FieldPath path) {
        List<Object> current = new ArrayList<>();
        current.add(q);
        for (String seg : path.segments()) {
            List<Object> next = new ArrayList<>();
            for (Object o : current) {
                if (o instanceof Viewable v) {
                    addExpanded(next, FieldSet.of(v).read(seg));
                } else if (o instanceof java.util.Map<?, ?> map) {
                    if (map.containsKey(seg)) {
                        addExpanded(next, map.get(seg));
                    } else {
                        for (Object value : map.values()) {
                            addExpanded(next, readPlain(value, seg));
                        }
                    }
                } else {
                    addExpanded(next, readPlain(o, seg));
                }
            }
            current = next;
        }
        return current;
    }

    private static Object readPlain(Object owner, String segment) {
        return owner == null ? null : objectview.field.FieldAccess.getPath(
                owner, FieldPath.of(segment));
    }

    /** Collections, maps and arrays are all multi-valued intermediates in a field path.
     *  Fan them out consistently so coverage follows the same nested object graph the
     *  field tree presents instead of silently ignoring non-Collection containers. */
    private static void addExpanded(List<Object> target, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            target.addAll(collection);
        } else if (value instanceof java.util.Map<?, ?> map) {
            target.addAll(map.values());
        } else if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                target.add(java.lang.reflect.Array.get(value, i));
            }
        } else {
            target.add(value);
        }
    }

    /** The single population rule for field-value scopes used by both the main workbench
     *  and curation. Eligibility is owner-type aware, so subtype fields never classify
     *  unrelated base instances as missing. */
    public static List<Viewable> select(
            DomainModel domain,
            Collection<? extends Viewable> source,
            String ownerType,
            FieldPath path,
            ScopeFilter filter) {
        if (domain == null || source == null || ownerType == null
                || path == null || filter == null) {
            return List.of();
        }
        return source.stream()
                .filter(Objects::nonNull)
                .filter(member -> domain.isInstanceOf(member, ownerType))
                .filter(member -> {
                    boolean present = hasValue(member, path);
                    return switch (filter) {
                        case ALL -> true;
                        case PRESENT -> present;
                        // A source that SAID "unknown" has answered; keeping it in
                        // MISSING makes a worklist item that can never be cleared.
                        case MISSING -> !present && assertedStatus(member, path) == null;
                        case ASSERTED_EMPTY ->
                                !present && assertedStatus(member, path) != null;
                        // A named-but-unresolved reference is a subset of PRESENT, so it
                        // needs the value to be there before the name can be missing.
                        case UNNAMED_REFERENCE ->
                                present && hasUnnamedReference(member, path);
                    };
                })
                .map(Viewable.class::cast)
                .toList();
    }

    private static ExtraColumn column(
            String header, int width, Function<FieldPath, Object> value) {
        return new ExtraColumn() {
            @Override public String header() { return header; }
            @Override public int width() { return width; }
            @Override public Object value(FieldRow row) { return value.apply(row.path()); }
        };
    }
}
