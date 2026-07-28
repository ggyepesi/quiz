package quiz.enrichment;

import process.Process;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessPlan;
import process.ProcessStatus;
import process.QuerySubprocess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reusable Find Data workflow. Providers are isolated subprocesses: one failure or
 * cancellation cannot discard candidates already completed by another provider.
 */
public final class FindDataProcess implements Process<FindDataResult> {
    private final EnrichmentRequest request;
    private final List<EnrichmentProvider> providers;
    private final boolean requestReview;
    private final ProcessPlan plan;

    public FindDataProcess(
            EnrichmentRequest request,
            List<EnrichmentProvider> providers,
            boolean requestReview) {
        this.request = request;
        this.providers = providers == null ? List.of() : providers.stream()
                .filter(provider -> provider.supports(request)).toList();
        this.requestReview = requestReview;
        if (this.providers.isEmpty()) {
            throw new IllegalArgumentException("No enrichment provider supports this subject");
        }
        this.plan = new ProcessPlan(
                "Find data — " + request.subject().displayName(),
                "Discover, combine and review data without losing completed provider results",
                Map.of("subject", request.subject().displayName(),
                        "field", request.targetField()),
                this.providers.stream()
                        .map(provider -> new ProcessPlan(
                                provider.name(), "Discover candidates", Map.of()))
                        .toList());
    }

    @Override public ProcessPlan plan() {
        return plan;
    }

    @Override public ProcessOutcome<FindDataResult> execute(ProcessContext context)
            throws Exception {
        List<EnrichmentProposal.IdentityCandidate> identities = new ArrayList<>();
        List<EnrichmentProposal.FieldCandidate> fields = new ArrayList<>();
        List<EnrichmentProposal.MediaCandidate> media = new ArrayList<>();
        Throwable firstProblem = null;
        int completed = 0;

        for (EnrichmentProvider provider : providers) {
            if (context.cancellation().isCancelled()) break;
            ProcessOutcome<EnrichmentProposal> outcome =
                    context.run(new QuerySubprocess<>(provider.discover(request)));
            if (outcome.usefulResult().isPresent()) {
                EnrichmentProposal proposal = outcome.usefulResult().get();
                identities.addAll(proposal.identities());
                fields.addAll(proposal.fields());
                media.addAll(proposal.media());
            }
            if (outcome.status() == ProcessStatus.SUCCEEDED
                    || outcome.status() == ProcessStatus.PARTIAL) {
                completed++;
            } else if (firstProblem == null) {
                firstProblem = outcome.error();
            }
        }

        EnrichmentProposal proposal = deduplicate(new EnrichmentProposal(
                request.subject(), identities, fields, media));
        FindDataResult discovered = new FindDataResult(proposal, null);

        if (context.cancellation().isCancelled()) {
            return ProcessOutcome.cancelled(discovered,
                    summary(proposal, "cancelled; completed results preserved"));
        }
        if (completed == 0 && firstProblem != null) {
            return ProcessOutcome.failed(firstProblem);
        }

        EnrichmentDecision accepted = null;
        if (requestReview && hasCandidates(proposal)) {
            accepted = context.input(new EnrichmentReviewRequest(
                    "Review enrichment — " + request.subject().displayName(),
                    "Review and accept the candidates returned by Find Data.",
                    proposal));
        }
        FindDataResult result = new FindDataResult(proposal, accepted);
        if (firstProblem != null || completed < providers.size()) {
            return ProcessOutcome.partial(result, firstProblem,
                    summary(proposal, "partial; completed results preserved"));
        }
        return ProcessOutcome.succeeded(result, summary(proposal, "complete"));
    }

    private static boolean hasCandidates(EnrichmentProposal proposal) {
        return !proposal.identities().isEmpty()
                || !proposal.fields().isEmpty()
                || !proposal.media().isEmpty();
    }

    private static String summary(EnrichmentProposal proposal, String state) {
        int count = proposal.identities().size()
                + proposal.fields().size() + proposal.media().size();
        return count + " candidate(s), " + state;
    }

    private static EnrichmentProposal deduplicate(EnrichmentProposal proposal) {
        Map<String, EnrichmentProposal.IdentityCandidate> identities = new LinkedHashMap<>();
        for (EnrichmentProposal.IdentityCandidate identity : proposal.identities()) {
            String key = identity.source().kind() + "\u0000"
                    + identity.source().sourceId() + "\u0000"
                    + identity.source().recordUrl();
            identities.putIfAbsent(key, identity);
        }
        Map<String, EnrichmentProposal.MediaCandidate> media = new LinkedHashMap<>();
        for (EnrichmentProposal.MediaCandidate candidate : proposal.media()) {
            media.putIfAbsent(candidate.imageUrl(), candidate);
        }
        return new EnrichmentProposal(
                proposal.subject(), new ArrayList<>(identities.values()),
                proposal.fields(), new ArrayList<>(media.values()));
    }
}
