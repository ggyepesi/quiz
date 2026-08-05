package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldSet;
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
    public record Scoped(String type, String path) { }

    /** One consistent coverage calculation shared by every column and by the explicit
     *  All / Missing / Present controls beside the table. */
    public record Coverage(int eligible, int present) {
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
    private final Map<String, Coverage> cache = new LinkedHashMap<>();
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
                column("Missing", 64, p -> String.valueOf(coverage(p).missing())));
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

    public Coverage coverage(String path) {
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
            String path) {
        Scoped scoped = scoped(currentType, path);
        if (scoped == null || domain == null) {
            return new Coverage(0, 0);
        }
        int eligible = 0;
        int present = 0;
        for (Viewable q : currentMembers) {
            if (domain.isInstanceOf(q, scoped.type())) {
                eligible++;
                if (hasValue(q, scoped.path())) {
                    present++;
                }
            }
        }
        return new Coverage(eligible, present);
    }

    /** Resolve a picker path (which may carry {@code @subtype:X} segments) to its owning
     *  type + plain field path, defaulting to {@code baseType}. */
    public static Scoped scoped(String baseType, String rawPath) {
        objectview.viewconfig.ViewConfigEditor.ResolvedFieldPath resolved =
                objectview.viewconfig.ViewConfigEditor.resolveFieldPath(baseType, rawPath);
        return resolved == null ? null : new Scoped(resolved.owner(), resolved.path());
    }

    /** Whether {@code q} has a non-empty value at the dotted {@code path} (descending
     *  through collection intermediates). */
    public static boolean hasValue(Viewable q, String path) {
        List<Object> current = new ArrayList<>();
        current.add(q);
        for (String seg : path.split("\\.")) {
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
        for (Object o : current) {
            if (o == null) {
                continue;
            }
            if (o instanceof String s && s.isBlank()) {
                continue;
            }
            if (o instanceof Collection<?> c && c.isEmpty()) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static Object readPlain(Object owner, String segment) {
        return owner == null ? null : objectview.field.FieldAccess.getPath(owner, segment);
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
            String path,
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
                    return filter == ScopeFilter.ALL
                            || filter == ScopeFilter.PRESENT && present
                            || filter == ScopeFilter.MISSING && !present;
                })
                .map(Viewable.class::cast)
                .toList();
    }

    private static ExtraColumn column(String header, int width, Function<String, Object> value) {
        return new ExtraColumn() {
            @Override public String header() { return header; }
            @Override public int width() { return width; }
            @Override public Object value(FieldRow row) { return value.apply(row.path()); }
        };
    }
}
