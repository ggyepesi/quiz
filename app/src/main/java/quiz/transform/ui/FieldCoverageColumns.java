package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldSet;
import objectview.viewconfig.FieldRow;
import objectview.viewconfig.FieldTableContributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

    private final DomainModel domain;
    private final Supplier<String> baseType;
    private final Supplier<? extends Collection<? extends Viewable>> instances;

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
                column("Coverage", 80, this::pct),
                column("Present", 64, p -> String.valueOf(present(p))),
                column("Missing", 64, p -> String.valueOf(eligibleCount(p) - present(p))));
    }

    private String pct(String path) {
        int total = eligibleCount(path);
        if (total == 0) {
            return "—";
        }
        return (Math.round(1000.0 * present(path) / total) / 10.0) + "%";
    }

    private int present(String path) {
        Scoped scoped = scoped(baseType.get(), path);
        if (scoped == null || domain == null) {
            return 0;
        }
        int n = 0;
        for (Viewable q : instances.get()) {
            if (domain.isInstanceOf(q, scoped.type()) && hasValue(q, scoped.path())) {
                n++;
            }
        }
        return n;
    }

    private int eligibleCount(String path) {
        Scoped scoped = scoped(baseType.get(), path);
        if (scoped == null || domain == null) {
            return 0;
        }
        int n = 0;
        for (Viewable q : instances.get()) {
            if (domain.isInstanceOf(q, scoped.type())) {
                n++;
            }
        }
        return n;
    }

    /** Resolve a picker path (which may carry {@code @subtype:X} segments) to its owning
     *  type + plain field path, defaulting to {@code baseType}. */
    public static Scoped scoped(String baseType, String rawPath) {
        if (rawPath == null || rawPath.isBlank() || baseType == null) {
            return null;
        }
        String scopedType = baseType;
        List<String> fieldSegments = new ArrayList<>();
        for (String segment : rawPath.split("\\.")) {
            if (segment.startsWith("@subtype:") && segment.length() > 9) {
                scopedType = segment.substring(9);
            } else if (!segment.isBlank()) {
                fieldSegments.add(segment);
            }
        }
        return fieldSegments.isEmpty() ? null
                : new Scoped(scopedType, String.join(".", fieldSegments));
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
                    Object val = FieldSet.of(v).read(seg);
                    if (val instanceof Collection<?> c) {
                        next.addAll(c);
                    } else if (val != null) {
                        next.add(val);
                    }
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

    private static ExtraColumn column(String header, int width, Function<String, Object> value) {
        return new ExtraColumn() {
            @Override public String header() { return header; }
            @Override public int width() { return width; }
            @Override public Object value(FieldRow row) { return value.apply(row.path()); }
        };
    }
}
