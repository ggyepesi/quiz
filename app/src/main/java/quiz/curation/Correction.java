package quiz.curation;

/**
 * One overlaid field value for an instance, applied to the base data AFTER it's
 * loaded (so it survives regeneration). Matched by {@code qid} (the instance
 * identifier) + {@code field} (a dotted path). Fresh records keep replay
 * {@link #policy} separate from structured {@link #source}; {@code origin} remains as
 * a compact legacy/source label so older sidecars continue to load unchanged.
 */
public record Correction(String type, String qid, String field, Object value,
                         String origin, String valueKind,
                         CorrectionPolicy policy, ValueSource source) {

    public static final String MANUAL = "manual";
    public static final String MEDIA = "media";
    public static final String MEDIA_COLLECTION = "media[]";

    /**
     * An entity-valued field. The stored value is the target's QID; the instance it
     * refers to is materialized at apply time, exactly as {@link #MEDIA} stores a URL
     * and materializes a MediaValue.
     *
     * <p>A QID, not the object: a sidecar records what was decided, and what was decided
     * is "this film's location is Q60". Serialising the target instead would freeze a
     * copy of it beside the pool that owns it.
     */
    public static final String REFERENCE = "reference";
    public static final String REFERENCE_COLLECTION = "reference[]";

    /** A human label belongs to the external entity identity, not to whichever domain
     *  class happened to reference it. Null type deliberately selects QID-wide replay. */
    public static Correction entityLabel(String qid, String label, String origin) {
        return new Correction(
                null, qid, objectview.field.ViewableContractFieldSet.DISPLAY_KEY,
                label, origin, null, CorrectionPolicy.REPLACE, null);
    }

    /** Backward-compatible shape used by generated and legacy sidecars. */
    public Correction(String qid, String field, Object value, String origin) {
        this(null, qid, field, value, origin, null, null, null);
    }

    /** Backward-compatible constructor used by pre-provenance code and tests. */
    public Correction(String type, String qid, String field, Object value,
                      String origin, String valueKind) {
        this(type, qid, field, value, origin, valueKind, null, null);
    }

    public boolean isManual() {
        return MANUAL.equals(origin);
    }

    /** Legacy sidecars encoded policy indirectly in origin; fresh records are explicit. */
    public CorrectionPolicy effectivePolicy() {
        return policy != null ? policy
                : isManual() ? CorrectionPolicy.REPLACE
                : CorrectionPolicy.FILL_IF_EMPTY;
    }

    /** Whether this directive takes precedence over ordinary fill-if-empty proposals. */
    public boolean authoritative() {
        return effectivePolicy() != CorrectionPolicy.FILL_IF_EMPTY
                && effectivePolicy() != CorrectionPolicy.EVIDENCE_ONLY;
    }
}
