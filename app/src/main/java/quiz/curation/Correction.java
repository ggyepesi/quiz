package quiz.curation;

/**
 * One overlaid field value for an instance, applied to the base data AFTER it's
 * loaded (so it survives regeneration). Matched by {@code qid} (the instance
 * identifier) + {@code field} (a dotted path). {@code origin} is provenance —
 * {@link #MANUAL}, a rule name, or an external source like {@code "wikipedia"} —
 * so a value can be presented as a reviewable suggestion, not a silent overwrite.
 */
public record Correction(String qid, String field, Object value, String origin) {

    public static final String MANUAL = "manual";

    public boolean isManual() {
        return MANUAL.equals(origin);
    }
}
