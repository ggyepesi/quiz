package quiz.enrichment;

import process.Process;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessPlan;
import process.ProcessStatus;
import process.QuerySubprocess;
import wikidata.explore.query.logical.ClassSearchQuery;
import wikidata.explore.query.result.TableQueryResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves the Wikidata identity (qid) for a SCOPE of instances (a plain list — whole
 * class, a group, a filter result, a coverage gap, …). It label-searches each instance,
 * gathers candidates, then a SINGLE review assigns qids. Field-independent: an instance has
 * one identity regardless of which fields it carries, so this runs once up front and every
 * later field configuration reads from the resolved entity.
 */
public final class ResolveIdentitiesProcess
        implements Process<ResolveIdentitiesDecision> {

    /** One instance to resolve: its domain id (where the identity link is written), its
     *  name (the search term), and its current qid (blank if not yet resolved). */
    public record Subject(String targetId, String name, String currentQid) { }

    private final List<Subject> subjects;
    private final int searchLimit;
    private final ProcessPlan plan;

    public ResolveIdentitiesProcess(List<Subject> subjects, int searchLimit) {
        this.subjects = subjects == null ? List.of() : List.copyOf(subjects);
        this.searchLimit = Math.max(1, searchLimit);
        if (this.subjects.isEmpty()) {
            throw new IllegalArgumentException("No instances to resolve");
        }
        this.plan = new ProcessPlan(
                "Resolve identities",
                "Search Wikidata for each instance, then review and assign the matches",
                Map.of("instances", Integer.toString(this.subjects.size())),
                List.of());
    }

    @Override public ProcessPlan plan() {
        return plan;
    }

    @Override public ProcessOutcome<ResolveIdentitiesDecision> execute(ProcessContext context)
            throws Exception {
        List<ResolveIdentitiesReviewRequest.InstanceIdentity> forReview = new ArrayList<>();
        Throwable firstProblem = null;
        int failedSearches = 0;
        int completedSearches = 0;
        int alreadyResolved = 0;
        for (Subject subject : subjects) {
            if (context.cancellation().isCancelled()) {
                break;
            }
            // An accepted identity is authoritative. Do not replace it with the first
            // result of a later label search; changing it is a separate explicit action.
            if (subject.currentQid() != null && !subject.currentQid().isBlank()) {
                alreadyResolved++;
                continue;
            }
            ProcessOutcome<TableQueryResult> search = context.run(
                    new QuerySubprocess<>(new ClassSearchQuery(
                            subject.name(), ClassSearchQuery.Mode.API, searchLimit)));
            List<ResolveIdentitiesReviewRequest.IdentityMatch> candidates = search.usefulResult()
                    .map(ResolveIdentitiesProcess::toMatches)
                    .orElse(List.of());
            if (search.status() == ProcessStatus.SUCCEEDED) {
                completedSearches++;
            } else {
                failedSearches++;
                if (firstProblem == null) {
                    firstProblem = search.error();
                }
            }
            forReview.add(new ResolveIdentitiesReviewRequest.InstanceIdentity(
                    subject.targetId(), subject.name(), "", candidates));
        }

        if (context.cancellation().isCancelled()) {
            return ProcessOutcome.cancelled(
                    new ResolveIdentitiesDecision(List.of()), "cancelled before review");
        }

        ResolveIdentitiesDecision decision = forReview.isEmpty()
                ? new ResolveIdentitiesDecision(List.of())
                : context.input(new ResolveIdentitiesReviewRequest(
                        "Resolve identities",
                        "Confirm the Wikidata entity for each instance.",
                        forReview));
        ResolveIdentitiesDecision result = decision == null
                ? new ResolveIdentitiesDecision(List.of()) : decision;
        String summary = result.resolved().size() + " resolved, "
                + alreadyResolved + " already resolved"
                + (failedSearches == 0 ? ""
                        : ", " + failedSearches + " search(es) failed");
        if (failedSearches > 0) {
            if (completedSearches == 0 && result.resolved().isEmpty()
                    && firstProblem != null) {
                return ProcessOutcome.failed(firstProblem);
            }
            return ProcessOutcome.partial(result, firstProblem, summary);
        }
        return ProcessOutcome.succeeded(result, summary);
    }

    private static List<ResolveIdentitiesReviewRequest.IdentityMatch> toMatches(
            TableQueryResult result) {
        List<ResolveIdentitiesReviewRequest.IdentityMatch> out = new ArrayList<>();
        if (result == null) {
            return out;
        }
        for (List<Object> row : result.rows()) {
            if (row.size() >= 2) {
                out.add(new ResolveIdentitiesReviewRequest.IdentityMatch(
                        str(row.get(0)), str(row.get(1)),
                        row.size() >= 3 ? str(row.get(2)) : ""));
            }
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
