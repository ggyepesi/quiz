package quiz.facet;

import quiz.Quizable;

import java.util.List;
import java.util.function.Function;

/**
 * A dimension to group members by. A facet maps each member to zero or more
 * <i>bucket keys</i>:
 * <ul>
 *   <li>a scalar field -> one key (its value);</li>
 *   <li>a collection/map field -> several keys (member appears in each);</li>
 *   <li>a reference field -> a {@link FacetKey} carrying the referenced object,
 *       so its bucket can show that object's card;</li>
 *   <li>a derived/mapped facet -> whatever the supplied function returns.</li>
 * </ul>
 * The same operation as {@code Quizable.generateUniqueCombinations}, lifted
 * from one object to the whole collection.
 */
public record Facet(String label, Function<Quizable, List<FacetKey>> keys) {

    /** Facet by a field, labelled by the field name. */
    public static Facet field(String fieldName) {
        return field(fieldName, fieldName);
    }

    /** Facet by a (value) field, with a display label. */
    public static Facet field(String fieldName, String label) {
        return new Facet(label, q -> values(FacetKeys.fromField(q, fieldName)));
    }

    /** A derived facet: value keys computed from the member (e.g. a predicate). */
    public static Facet derived(String label, Function<Quizable, List<String>> keys) {
        return new Facet(label, q -> values(keys.apply(q)));
    }

    /**
     * A facet derived by mapping another field's value(s) — e.g. Country from a
     * State field. Each of the field's keys is passed through {@code map};
     * null/blank results are dropped, duplicates collapsed.
     */
    public static Facet mapped(String label, String fieldName, Function<String, String> map) {
        return new Facet(label, q -> values(
                FacetKeys.fromField(q, fieldName).stream()
                        .map(map)
                        .filter(s -> s != null && !s.isBlank())
                        .distinct()
                        .toList()));
    }

    /** Facet by a reference field, labelled by the field name. */
    public static Facet reference(String fieldName) {
        return reference(fieldName, fieldName);
    }

    /**
     * Facet by a field whose value(s) are themselves {@link Quizable}s — the
     * member is bucketed under each referenced entity, and the bucket carries
     * that entity so the UI can render its card.
     */
    public static Facet reference(String fieldName, String label) {
        return new Facet(label, q ->
                FacetKeys.refsFromField(q, fieldName).stream()
                        .map(FacetKey::of)
                        .filter(k -> k != null && k.isUsable())
                        .toList());
    }

    private static List<FacetKey> values(List<String> names) {
        return names.stream().map(FacetKey::of).filter(FacetKey::isUsable).toList();
    }
}
