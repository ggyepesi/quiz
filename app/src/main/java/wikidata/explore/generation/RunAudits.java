package wikidata.explore.generation;

import java.util.ArrayList;
import java.util.List;

/**
 * What a run did, in words, for the places a card cannot reach.
 *
 * <p>The results window shows each rule as a bucket you can open, and it is gone the
 * moment the run is accepted — taking the only account of what happened with it. The run
 * itself still holds all of it, so the loss is of the VIEW rather than the data; this is
 * the cheapest way to keep the account, in a log that persists and is saved alongside.
 *
 * <p>It reports each rule's status even when the rule found nothing, because "ran and
 * found nothing" and "was not run at all" are the two answers this whole line of work
 * exists to keep apart.
 */
public final class RunAudits {

    private RunAudits() {}

    /** One multi-line account of the run, always non-empty. */
    public static String report(GenerationRun run) {
        if (run == null) {
            return "No run to report.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("What this run did:");
        lines.add("  Rules that named instances: "
                + RuleEffects.describe(RuleEffects.fromRun(
                        run.fieldCoverage(), run.selfReferenceAudit(),
                        run.ownedCompositionAudit(), run.kindClassificationAudit(),
                        run.projectionAudit())));
        lines.add("  Self-reference: " + run.selfReferenceAudit().description());
        lines.add("  Owned composition: " + run.ownedCompositionAudit().description());
        lines.add("  Kind classification: " + run.kindClassificationAudit().description());
        lines.add("  Projections: " + run.projectionAudit().description());
        String coverage = CoverageReport.message(run.fieldCoverage());
        if (!coverage.isEmpty()) {
            lines.add("  " + coverage);
        }
        lines.add("  Objects in the pool: " + run.size());
        return String.join("\n", lines);
    }
}
