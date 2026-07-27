package quiz.curation;

/**
 * One overlaid field value for an instance, applied to the base data AFTER it's
 * loaded (so it survives regeneration). Matched by {@code qid} (the instance
 * identifier) + {@code field} (a dotted path). {@code origin} is provenance —
 * {@link #MANUAL}, a rule name, or an external source like {@code "wikipedia"} —
 * so a value can be presented as a reviewable suggestion, not a silent overwrite.
 */
public record Correction(String type, String qid, String field, Object value,
                         String origin, String valueKind) {

    public static final String MANUAL = "manual";
    public static final String MEDIA = "media";
    public static final String MEDIA_COLLECTION = "media[]";

    /** Backward-compatible shape used by generated and legacy sidecars. */
    public Correction(String qid, String field, Object value, String origin) {
        this(null, qid, field, value, origin, null);
    }

    public boolean isManual() {
        return MANUAL.equals(origin);
    }
}
