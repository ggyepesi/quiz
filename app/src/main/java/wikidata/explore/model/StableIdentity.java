package wikidata.explore.model;

import objectview.Viewable;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Structural identity encoding for model key values. A value type's own
 * process-independent form wins; references use their identifier; collections are
 * order-independent and include every member.
 */
public final class StableIdentity {

    private StableIdentity() {}

    public static String of(Object value) {
        if (value == null) return "";
        if (value instanceof aux.StableValue stable) {
            return safe(stable.stableForm());
        }
        if (value instanceof Viewable viewable) {
            return safe(viewable.getIdentifier());
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(StableIdentity::of)
                    .sorted()
                    .collect(Collectors.joining(",", "[", "]"));
        }
        return value.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
