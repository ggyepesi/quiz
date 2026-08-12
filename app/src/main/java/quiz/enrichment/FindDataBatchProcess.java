package quiz.enrichment;

import process.Process;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessPlan;
import process.ProcessStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one reusable Find Data subprocess per member (each member's job is discovery
 * ONLY — no per-member review), then presents a SINGLE batch review over every member's
 * proposal and applies the accepted values. Each child has its own cancellation/log
 * scope; completed earlier members survive a later failure or cancellation, and the one
 * review means a "fill this field for N members" run is one screen, not N dialogs.
 */
public final class FindDataBatchProcess implements Process<FindDataBatchResult> {
    private final List<FindDataProcess> members;
    private final int skipped;
    private final String field;
    private final ProcessPlan plan;

    public FindDataBatchProcess(List<FindDataProcess> members, int skipped, String field) {
        this.members = members == null ? List.of() : List.copyOf(members);
        this.skipped = Math.max(0, skipped);
        this.field = field == null ? "" : field;
        if (this.members.isEmpty()) {
            throw new IllegalArgumentException("Find Data batch has no eligible members");
        }
        this.plan = new ProcessPlan(
                "Find data batch",
                "Discover data for every eligible member, then review and apply in one step",
                Map.of("field", this.field,
                        "members", Integer.toString(this.members.size()),
                        "skipped", Integer.toString(this.skipped)),
                this.members.stream().map(FindDataProcess::plan).toList());
    }

    @Override public ProcessPlan plan() {
        return plan;
    }

    @Override public ProcessOutcome<FindDataBatchResult> execute(ProcessContext context)
            throws Exception {
        List<FindDataResult> results = new ArrayList<>();
        List<EnrichmentProposal> reviewable = new ArrayList<>();
        Throwable firstProblem = null;
        int incomplete = 0;

        for (FindDataProcess member : members) {
            if (context.cancellation().isCancelled()) break;
            ProcessOutcome<FindDataResult> outcome = context.run(member);
            if (outcome.usefulResult().isPresent()) {
                FindDataResult memberResult = outcome.usefulResult().get();
                results.add(memberResult);
                // Review EVERY member — with a value and without — so the not-found members
                // surface in the review's "Not found" section (the shared review panel that
                // identity resolution's "No match" also uses) instead of silently vanishing.
                reviewable.add(memberResult.proposal());
            }
            if (outcome.status() != ProcessStatus.SUCCEEDED) {
                incomplete++;
                if (firstProblem == null) firstProblem = outcome.error();
            }
        }

        // Execution ends with immutable raw proposals. The workflow host owns the
        // Results and Apply phases; a Process must never pause to construct Swing UI.
        FindDataBatchResult result = new FindDataBatchResult(results, skipped);
        String summary = reviewable.size() + " result(s) ready for review, "
                + results.size() + "/" + members.size() + " member(s) completed"
                + (skipped == 0 ? "" : ", " + skipped + " skipped");
        if (context.cancellation().isCancelled()) {
            return ProcessOutcome.cancelled(result, summary);
        }
        if (results.isEmpty() && firstProblem != null) {
            return ProcessOutcome.failed(firstProblem);
        }
        if (incomplete > 0 || skipped > 0) {
            return ProcessOutcome.partial(result, firstProblem, summary);
        }
        return ProcessOutcome.succeeded(result, summary);
    }
}
