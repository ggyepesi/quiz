package datasource.api;

import java.util.Arrays;

/**
 * A named, replaceable role occupied by one source recipe at a binding site.
 *
 * <p>The names describe model semantics rather than providers. The persisted ids of the
 * two field slots retain their legacy sidecar spelling until that format is migrated.
 */
public enum SourceBindingSlot {
    CLASS_POPULATION("population", BindingScope.CLASS_POPULATION),
    CATEGORY_EVIDENCE("wikipedia-category", BindingScope.FIELD_VALUE),
    FALLBACK_FIELD_VALUE("additional-source", BindingScope.FIELD_VALUE);

    private final String id;
    private final BindingScope scope;

    SourceBindingSlot(String id, BindingScope scope) {
        this.id = id;
        this.scope = scope;
    }

    public String id() { return id; }
    public BindingScope scope() { return scope; }

    public static SourceBindingSlot require(String id) {
        String clean = id == null ? "" : id.trim();
        return Arrays.stream(values()).filter(slot -> slot.id.equals(clean)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown source binding slot: " + clean));
    }
}
