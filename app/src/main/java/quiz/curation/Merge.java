package quiz.curation;

import java.util.Map;

/**
 * A curation directive to MERGE one instance into another: {@code duplicate} (matched
 * by identifier) is folded into {@code primary} — the primary keeps its identity, the
 * duplicate is removed from the pool. Applied AFTER load (an overlay, like {@link
 * Correction}), so it survives a regeneration. {@code origin} is provenance.
 *
 * <p>{@code fieldSource} records the per-field resolution the curator approved in the
 * preview: {@link #PRIMARY} keep the primary's value, {@link #DUPLICATE} take the
 * duplicate's, {@link #BOTH} union them. A field absent from the map uses the default
 * (fill an empty primary, union collections/maps, else the primary wins).</p>
 */
public record Merge(String type, String primary, String duplicate,
                    Map<String, String> fieldSource, String origin) {

    public static final String MANUAL = "manual";
    public static final String PRIMARY = "primary";
    public static final String DUPLICATE = "duplicate";
    public static final String BOTH = "both";

    public Merge {
        fieldSource = fieldSource == null ? Map.of() : Map.copyOf(fieldSource);
    }

    /** The default (auto) merge — no per-field overrides. */
    public Merge(String primary, String duplicate, String origin) {
        this(null, primary, duplicate, Map.of(), origin);
    }

    public Merge(String primary, String duplicate,
                 Map<String, String> fieldSource, String origin) {
        this(null, primary, duplicate, fieldSource, origin);
    }

    /** The approved source for {@code field}, or null for the default resolution. */
    public String sourceFor(String field) {
        return fieldSource.get(field);
    }
}
