package domain.datasource.api;

import java.util.Arrays;

/**
 * A named role occupied by source configuration at one binding site. Whether a role holds
 * one recipe or an ordered list of independently configured recipes is stated here, by
 * {@link #additive()}, and every consumer asks that instead of naming a slot.
 *
 * <p>The names describe model semantics rather than providers. The persisted ids of the
 * two field slots retain their legacy sidecar spelling until that format is migrated.
 */
public enum SourceBindingSlot {
    CLASS_POPULATION("population", BindingScope.CLASS_POPULATION),
    CLASS_IDENTITY("identity", BindingScope.CLASS_IDENTITY),
    CLASS_LABEL("label", BindingScope.CLASS_NAMES),
    CLASS_ALIASES("alternate-names", BindingScope.CLASS_NAMES),
    ARTICLE_CORRESPONDENCE("article-correspondence", BindingScope.SOURCE_CORRESPONDENCE),
    PRIMARY_FIELD_VALUE("primary", BindingScope.FIELD_VALUE),
    CATEGORY_EVIDENCE("wikipedia-category", BindingScope.FIELD_VALUE),
    /** Review-only candidates derived from a retained source document. This is separate
     *  from the primary value: accepting one is an explicit curation decision. */
    CANDIDATE_EVIDENCE("candidate-evidence", BindingScope.FIELD_VALUE, true),
    FALLBACK_FIELD_VALUE("additional-source", BindingScope.FIELD_VALUE);

    private final String id;
    private final BindingScope scope;
    private final boolean additive;

    SourceBindingSlot(String id, BindingScope scope) {
        this(id, scope, false);
    }

    SourceBindingSlot(String id, BindingScope scope, boolean additive) {
        this.id = id;
        this.scope = scope;
        this.additive = additive;
    }

    public String id() { return id; }
    public BindingScope scope() { return scope; }

    /** True when the slot holds an ordered list of recipes, each a visible child that is
     *  configured and run on its own; false when one recipe replaces another. */
    public boolean additive() { return additive; }

    public static SourceBindingSlot require(String id) {
        String clean = id == null ? "" : id.trim();
        return Arrays.stream(values()).filter(slot -> slot.id.equals(clean)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown source binding slot: " + clean));
    }
}
