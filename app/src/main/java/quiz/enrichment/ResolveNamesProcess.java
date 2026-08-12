package quiz.enrichment;

import process.Process;
import process.ProcessContext;
import process.ProcessInputRequest;
import process.ProcessOutcome;
import process.ProcessPlan;
import wikidata.api.WikidataEntityLabelResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repairs the names of referenced entities that still show their QID: resolve, review,
 * apply — the road every other bulk curation takes.
 *
 * <p>Written as a {@link Process} rather than a bare query so it inherits what that road
 * already provides: a cancellable run (a 39-request repair cannot be a frozen button), a
 * plan and per-batch entries in the query log, and a single review before anything is
 * staged. Run outside it, the same work reported one summary line, could not be
 * cancelled, and staged without asking.
 */
public final class ResolveNamesProcess implements Process<ResolveNamesProcess.Result> {

    /** One accepted repair: the entity and the name it will be given. */
    public record Name(String qid, String label) { }

    /** Raw query outcome. Review and staging are workflow-host responsibilities. */
    public record Result(
            Set<String> requested, Map<String, String> resolved, int failedBatches) {
        public Result {
            requested = requested == null ? Set.of() : Set.copyOf(requested);
            resolved = resolved == null ? Map.of() : Map.copyOf(resolved);
        }
    }

    /** Review over the outcome — resolved names to accept, unresolved kept visible. */
    public record ReviewRequest(
            String title,
            String field,
            Set<String> requested,
            Map<String, String> resolved,
            int failedBatches) implements ProcessInputRequest<List<Name>> {

        @Override public String prompt() {
            return resolved.size() + " of " + requested.size()
                    + " referenced entities now have a name";
        }

        @SuppressWarnings("unchecked")
        @Override public Class<List<Name>> responseType() {
            return (Class<List<Name>>) (Class<?>) List.class;
        }
    }

    private static final int BATCH = 50;

    private final Set<String> qids;
    private final String field;
    private final ProcessPlan plan;

    public ResolveNamesProcess(Set<String> qids, String field) {
        this.qids = qids == null ? Set.of() : Set.copyOf(qids);
        this.field = field == null ? "" : field;
        if (this.qids.isEmpty()) {
            throw new IllegalArgumentException("No unnamed references to resolve");
        }
        this.plan = new ProcessPlan(
                "Resolve reference names",
                "Fetch the missing labels for referenced entities, then review and apply",
                Map.of("field", this.field,
                        "references", Integer.toString(this.qids.size()),
                        "batches", Integer.toString(
                                (this.qids.size() + BATCH - 1) / BATCH)));
    }

    @Override public ProcessPlan plan() {
        return plan;
    }

    @Override public ProcessOutcome<Result> execute(ProcessContext context)
            throws Exception {

        List<String> all = new ArrayList<>(qids);
        Map<String, String> labels = new LinkedHashMap<>();
        int failedBatches = 0;

        // Batched HERE rather than inside one opaque call, so each request is its own
        // log entry and the cancellation is checked between them — a repair the user
        // stops should stop, not run to completion and then report.
        for (int from = 0; from < all.size(); from += BATCH) {
            if (context.cancellation().isCancelled()) {
                return ProcessOutcome.cancelled(
                        new Result(qids, labels, failedBatches),
                        "Cancelled after " + labels.size() + " of " + all.size()
                                + " name(s)");
            }
            List<String> batch = all.subList(from, Math.min(from + BATCH, all.size()));
            WikidataEntityLabelResolver.Result resolved =
                    new WikidataEntityLabelResolver(context.queries().api()).resolve(
                            batch, WikidataEntityLabelResolver.Execution.SEQUENTIAL,
                            (title, request, summary) ->
                                    context.message(title + " — " + summary + "\n"));
            labels.putAll(resolved.labels());
            failedBatches += resolved.failedBatches();
            context.message("Names " + labels.size() + "/" + all.size()
                    + " resolved so far.\n");
        }

        Result result = new Result(qids, labels, failedBatches);
        return ProcessOutcome.succeeded(
                result, labels.size() + " of " + all.size()
                        + " name(s) ready for review; " + failedBatches
                        + " batch(es) failed");
    }
}
