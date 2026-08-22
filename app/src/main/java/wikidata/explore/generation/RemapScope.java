package wikidata.explore.generation;

import java.util.List;

/**
 * What a Remap can actually re-run on this run (#93).
 *
 * <p>Remap re-transforms offline, and how much of the transform sequence it can redo
 * depends on what the run still holds. A run produced by this session's Generate keeps
 * the ENRICHED pool — the objects as they were before reification — so the whole
 * sequence can be replayed from there. A run loaded from a saved snapshot does not: its
 * pool is already reified and inverted, and replaying those stages would apply them a
 * second time. The idempotent, overwrite-only stages still take effect, which is why a
 * projection added since the snapshot was saved does fill on Remap.
 *
 * <p>The distinction used to be an unnamed {@code if} inside the pipeline, so a Remap
 * after an app restart reported success while quietly doing a fraction of the work — the
 * user edited a reify role, pressed Remap, saw "N objects" and believed it had applied.
 * Naming the scope lets both the log and the workflow plan say which stages will run and,
 * more importantly, which will not.
 */
public record RemapScope(boolean retransform, List<String> skippedStages) {

    /** The stages that need the enriched pool, in the order the pipeline runs them. */
    private static final List<String> NEEDS_ENRICHED_POOL = List.of(
            "reify", "field-value restrictions", "inverts", "companion match");

    public RemapScope {
        skippedStages = List.copyOf(skippedStages == null ? List.of() : skippedStages);
    }

    /** Read the scope off the run being remapped. */
    public static RemapScope of(GenerationRun previous) {
        GenerationRun.RemapState state =
                previous == null ? null : previous.remapState();
        boolean enriched = state != null
                && state.enrichedPool() != null
                && !state.enrichedPool().isEmpty();
        return enriched
                ? new RemapScope(true, List.of())
                : new RemapScope(false, NEEDS_ENRICHED_POOL);
    }

    /** A plain sentence naming what this Remap will NOT apply — empty when it applies
     *  everything, so a caller can append it unconditionally. */
    public String limitation() {
        if (retransform) {
            return "";
        }
        return "This run was loaded rather than generated in this session, so it no "
                + "longer has the pre-reification pool: " + String.join(", ", skippedStages)
                + " cannot re-run and changes to them will NOT be applied. "
                + "Run Generate to apply those.";
    }

    /** How the workflow plan describes this Remap before it runs. */
    public String describe() {
        return retransform
                ? "Re-runs the full transform sequence on the enriched pool."
                : "Re-runs only the idempotent transforms. " + limitation();
    }
}
