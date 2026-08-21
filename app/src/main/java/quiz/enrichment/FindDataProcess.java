package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;

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
    private final EnrichmentRoute route;
    private final boolean requestReview;
    private final ProcessPlan plan;

    public FindDataProcess(
            EnrichmentRequest request,
            List<EnrichmentProvider> providers,
            boolean requestReview) {
        this(request, EnrichmentRoute.all(providers), requestReview);
    }

    public FindDataProcess(
            EnrichmentRequest request,
            EnrichmentRoute route,
            boolean requestReview) {
        this.request = request;
        this.route = (route == null ? EnrichmentRoute.all(List.of()) : route)
                .applicableTo(request);
        this.requestReview = requestReview;
        if (this.route.isEmpty()) {
            throw new IllegalArgumentException("No enrichment provider supports this subject");
        }
        this.plan = new ProcessPlan(
                "Find data — " + request.subject().displayName(),
                "Route, combine and review data without losing completed provider results",
                Map.of("subject", request.subject().displayName(),
                       "field", request.targetField(),
                       "primary", providerNames(this.route.primary()),
                       "fallback", providerNames(this.route.fallback())),
                routePlans(this.route));
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
        int attempted = 0;

        // Primary sources retain the old combine-all behaviour: this is important for
        // image discovery, where Wikimedia and an originating record page may each add
        // useful candidates.
        for (EnrichmentProvider provider : route.primary()) {
            if (context.cancellation().isCancelled()) break;
            attempted++;
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

        // Only gaps proceed to fallback sources. An incompatible or ignored primary value
        // is still a gap, while identity metadata by itself must not suppress field routing.
        EnrichmentProposal routed = proposal(identities, fields, media);
        for (EnrichmentProvider provider : route.fallback()) {
            if (context.cancellation().isCancelled()
                    || routed.hasUsableCandidate(request.targetField())) {
                break;
            }
            attempted++;
            ProcessOutcome<EnrichmentProposal> outcome =
                    context.run(new QuerySubprocess<>(provider.discover(request)));
            if (outcome.usefulResult().isPresent()) {
                EnrichmentProposal discovered = outcome.usefulResult().get();
                identities.addAll(discovered.identities());
                fields.addAll(discovered.fields());
                media.addAll(discovered.media());
                routed = proposal(identities, fields, media);
            }
            if (outcome.status() == ProcessStatus.SUCCEEDED
                    || outcome.status() == ProcessStatus.PARTIAL) {
                completed++;
            } else if (firstProblem == null) {
                firstProblem = outcome.error();
            }
        }

        EnrichmentProposal proposal = proposal(identities, fields, media);
        FindDataResult discovered = new FindDataResult(proposal, null);

        if (context.cancellation().isCancelled()) {
            return ProcessOutcome.cancelled(discovered,
                                            summary(proposal, "cancelled; completed results preserved"));
        }
        if (completed == 0 && firstProblem != null) {
            return ProcessOutcome.failed(firstProblem);
        }

        // Discovery returns raw candidates. Review belongs to the workflow host so the
        // Running phase never blocks on UI and every caller follows the same skeleton.
        FindDataResult result = discovered;
        if (firstProblem != null || completed < attempted) {
            return ProcessOutcome.partial(result, firstProblem,
                                          summary(proposal, routeState(attempted, "partial; completed results preserved")));
        }
        return ProcessOutcome.succeeded(result,
                                        summary(proposal, routeState(attempted, "complete")));
    }

    private static boolean hasCandidates(EnrichmentProposal proposal) {
        return !proposal.identities().isEmpty()
                || !proposal.fields().isEmpty()
                || !proposal.media().isEmpty();
    }

    private static String summary(EnrichmentProposal proposal, String state) {
        long corroborations = proposal.corroborationCount();
        long count = proposal.identities().size()
                + proposal.fields().size() + proposal.media().size() - corroborations;
        return count + " candidate(s)"
                + (corroborations == 0 ? "" : " and " + corroborations
                        + " corroboration(s) of what is already there")
                + ", " + state;
    }

    private String routeState(int attempted, String state) {
        int skipped = route.allProviders().size() - attempted;
        return state + (skipped <= 0 ? "" : "; " + skipped + " fallback source(s) skipped");
    }

    private EnrichmentProposal proposal(
            List<EnrichmentProposal.IdentityCandidate> identities,
            List<EnrichmentProposal.FieldCandidate> fields,
            List<EnrichmentProposal.MediaCandidate> media) {
        return deduplicate(new EnrichmentProposal(
                request.subject(), identities, fields, media));
    }

    private static String providerNames(List<EnrichmentProvider> providers) {
        return String.join(", ", providers.stream().map(EnrichmentProvider::name).toList());
    }

    private static List<ProcessPlan> routePlans(EnrichmentRoute route) {
        List<ProcessPlan> plans = new ArrayList<>();
        for (EnrichmentProvider provider : route.primary()) {
            plans.add(new ProcessPlan(provider.name(), "Discover primary candidates", Map.of()));
        }
        for (EnrichmentProvider provider : route.fallback()) {
            plans.add(new ProcessPlan(provider.name(),
                                      "Discover only if earlier sources found no usable value", Map.of()));
        }
        return List.copyOf(plans);
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
