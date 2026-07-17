package objectview.facet;

import objectview.Viewable;

/**
 * One bucket key produced by a {@link Facet} for a member.
 *
 * <ul>
 *   <li>a <b>value</b> key carries just a {@code name} (League "NBA");</li>
 *   <li>a <b>reference</b> key also carries the {@code ref} object it names
 *       (the {@code Language} or parent {@code Creature}), so the resulting
 *       bucket can show that entity's own card.</li>
 * </ul>
 */
public record FacetKey(String name, Viewable ref) {

    public static FacetKey of(String name) {
        return new FacetKey(name, null);
    }

    public static FacetKey of(Viewable ref) {
        return ref == null ? null : new FacetKey(ref.getDisplayName(), ref);
    }

    public boolean isUsable() {
        return name != null && !name.isBlank();
    }
}
