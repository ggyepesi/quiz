package quiz.curation;

/**
 * A curation directive to MERGE one instance into another: {@code duplicate} (matched
 * by identifier) is folded into {@code primary} — the primary keeps its identity and
 * its scalar values, gains any field the duplicate has that it lacks, and unions
 * collections/maps — then the duplicate is removed from the pool. Applied AFTER load
 * (an overlay, like {@link Correction}), so it survives a regeneration. {@code origin}
 * is provenance (manual, or a future auto-dedup proposer).
 */
public record Merge(String primary, String duplicate, String origin) {

    public static final String MANUAL = "manual";
}
