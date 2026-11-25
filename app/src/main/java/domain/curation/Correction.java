package domain.curation;

/**
 * One overlaid field value for an instance, applied to the base data AFTER it's
 * loaded (so it survives regeneration). Matched by {@code qid} (the instance
 * identifier) + {@code field} (a dotted path). Fresh records keep replay
 * {@link #policy} separate from structured {@link #source}; {@code origin} remains as
 * a compact legacy/source label so older sidecars continue to load unchanged.
 */
public record Correction(String type, String qid, String field, Object value,
                         String origin, String valueKind,
                         CorrectionPolicy policy, ValueSource source,
                         String referenceType) {

    public Correction {
        referenceType = referenceType == null ? "" : referenceType.trim();
        // A reference names one configured class as well as one identifier; resolving by
        // identifier alone could attach an incompatible same-ID object (directive 9).
        if ((REFERENCE.equals(valueKind) || REFERENCE_COLLECTION.equals(valueKind))
                && referenceType.isEmpty()
                && !(REFERENCE_COLLECTION.equals(valueKind) && value instanceof java.util.List<?>)) {
            throw new IllegalArgumentException("Reference correction " + type + " " + qid
                    + "." + field + " -> " + value
                    + " does not name its target class (referenceType); it cannot be resolved.");
        }
    }

    /** Shape for corrections whose value is not a reference. */
    public Correction(String type, String qid, String field, Object value,
                      String origin, String valueKind,
                      CorrectionPolicy policy, ValueSource source) {
        this(type, qid, field, value, origin, valueKind, policy, source, "");
    }

    public static final String MANUAL = "manual";
    public static final String MEDIA = "media";
    public static final String MEDIA_COLLECTION = "media[]";
    public static final String DATE = "date";
    public static final String DATE_COLLECTION = "date[]";

    /**
     * An entity-valued field. The stored value is the target's source identifier and,
     * for fresh records, its configured type; the instance it refers to is materialized
     * at apply time, exactly as {@link #MEDIA} stores a URL and materializes a MediaValue.
     *
     * <p>An identity, not the object: a sidecar records what was decided, and what was
     * decided is "this film's location is the Location Q60". Serialising the target
     * instead would freeze a copy of it beside the pool that owns it. A reference without
     * its class is refused when it is created or loaded.
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
        this(null, qid, field, value, origin, null, null, null, "");
    }

    /** Backward-compatible constructor used by pre-provenance code and tests. */
    public Correction(String type, String qid, String field, Object value,
                      String origin, String valueKind) {
        this(type, qid, field, value, origin, valueKind, null, null, "");
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

    /** Collection additions are independent reviewed contributions, not competing
     * replacements for the field's one storage slot. */
    boolean additive() {
        return effectivePolicy() == CorrectionPolicy.ADD_TO_COLLECTION;
    }

    /** Same corrected field, treating a legacy untyped record as the same owner slot. */
    boolean sameTargetField(Correction other) {
        return other != null
                && (type == null || other.type == null
                        || java.util.Objects.equals(type, other.type))
                && java.util.Objects.equals(qid, other.qid)
                && java.util.Objects.equals(field, other.field);
    }

    /** Whether writing this correction replaces an existing durable entry. */
    boolean replacesExisting(Correction existing) {
        if (!sameTargetField(existing)) return false;
        if (!additive()) return true;
        return existing.additive() && sameContribution(existing);
    }

    /** Exact persisted entry identity, used when restoring a failed transaction. */
    boolean sameStoredEntry(Correction other) {
        if (!sameTargetField(other) || additive() != other.additive()) return false;
        return !additive() || sameContribution(other);
    }

    private boolean sameContribution(Correction other) {
        return java.util.Objects.deepEquals(value, other.value)
                && java.util.Objects.equals(valueKind, other.valueKind)
                && java.util.Objects.equals(referenceType, other.referenceType);
    }
}
