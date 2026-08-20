package wikidata.explore.generation;

import process.ProcessOutcome;
import process.ProcessStatus;

/**
 * What an incomplete run means — one decision, for every operation that produces one.
 *
 * <p>The run itself always survives. A load over thousands of entities normally answers
 * for nearly all of them, and a failed outcome carries no result at all, so refusing
 * incompleteness by failing threw away the data the run had already produced. The policy
 * chooses between "staged, and accepted deliberately" and "accepted, and said so".
 */
public final class RunCompleteness {

    private RunCompleteness() {}

    public static ProcessOutcome<GenerationRun> decide(
            ProcessOutcome<GenerationRun> outcome, boolean requireComplete) {
        if (outcome == null || outcome.status() != ProcessStatus.SUCCEEDED) return outcome;
        GenerationRun run = outcome.result();
        if (run == null || run.quality().complete()) return outcome;

        String warnings = String.join("; ", run.quality().warnings());
        return requireComplete
                ? ProcessOutcome.partial(run, null, outcome.summary()
                        + " — incomplete, not accepted automatically: " + warnings)
                : ProcessOutcome.succeeded(run,
                        outcome.summary() + " — incomplete: " + warnings);
    }
}
