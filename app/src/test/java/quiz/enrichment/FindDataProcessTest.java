package quiz.enrichment;

import org.junit.jupiter.api.Test;
import process.CancellationToken;
import process.ProcessInputHandler;
import process.ProcessInputRequest;
import process.ProcessOutcome;
import process.ProcessRunner;
import process.ProcessStatus;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindDataProcessTest {

    @Test
    void keepsCompletedProviderCandidatesWhenAnotherProviderFails() {
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Person", "Q1", "Someone"),
                "portrait", false, List.of());
        EnrichmentProposal.MediaCandidate candidate =
                new EnrichmentProposal.MediaCandidate(
                        "one", "identity", "portrait",
                        "https://example.test/image.jpg",
                        "https://example.test/image.jpg",
                        new EnrichmentProposal.SourceRef("Test", "1", "https://example.test/1"),
                        "test", 1.0, "", "", false);

        EnrichmentProvider succeeds = provider("Good", context ->
                new EnrichmentProposal(request.subject(), List.of(), List.of(), List.of(candidate)));
        EnrichmentProvider fails = provider("Bad", context -> {
            throw new IllegalStateException("provider down");
        });

        ProcessOutcome<FindDataResult> outcome = new ProcessRunner(
                new QueryContext(null, null), null, ProcessInputHandler.unsupported())
                .run(new FindDataProcess(request, List.of(succeeds, fails), false),
                        new CancellationToken());

        assertEquals(ProcessStatus.PARTIAL, outcome.status());
        assertNotNull(outcome.result());
        assertEquals(List.of(candidate), outcome.result().proposal().media());
    }

    @Test
    void batchKeepsEarlierMemberWhenLaterMemberFails() {
        EnrichmentRequest firstRequest = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Person", "one", "Q1", "One"),
                "population", false, List.of());
        EnrichmentRequest secondRequest = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Person", "two", "Q2", "Two"),
                "population", false, List.of());
        EnrichmentProvider succeeds = provider("Good", context ->
                new EnrichmentProposal(firstRequest.subject(), List.of(), List.of(), List.of()));
        EnrichmentProvider fails = provider("Bad", context -> {
            throw new IllegalStateException("provider down");
        });

        // The batch now reviews every member (so not-found ones are shown), so answer the
        // one review pause by accepting nothing — the point here is member-failure resilience.
        ProcessInputHandler acceptNothing = new ProcessInputHandler() {
            @Override public <T> T request(
                    ProcessInputRequest<T> req, CancellationToken cancellation) {
                return req.responseType().cast(new BatchReviewDecision(List.of()));
            }
        };
        ProcessOutcome<FindDataBatchResult> outcome = new ProcessRunner(
                new QueryContext(null, null), null, acceptNothing)
                .run(new FindDataBatchProcess(List.of(
                                new FindDataProcess(firstRequest, List.of(succeeds), false),
                                new FindDataProcess(secondRequest, List.of(fails), false)),
                                0, "population"),
                        new CancellationToken());

        assertEquals(ProcessStatus.PARTIAL, outcome.status());
        assertNotNull(outcome.result());
        assertEquals(1, outcome.result().results().size());
        assertEquals("one",
                outcome.result().results().get(0).proposal().subject().targetId());
    }

    @Test
    void batchReviewsEveryMemberOnceAndAppliesAcceptedValues() throws Exception {
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", "hawaii", "Q782", "Hawaii"),
                "population", false, List.of());
        EnrichmentProposal.SourceRef source =
                new EnrichmentProposal.SourceRef("Wikidata", "Q782", "url");
        EnrichmentProposal.IdentityCandidate identity =
                new EnrichmentProposal.IdentityCandidate(
                        "wikimedia-wikidata", "Hawaii", List.of(), "", source, 1.0, List.of());
        EnrichmentProposal.FieldCandidate value = new EnrichmentProposal.FieldCandidate(
                "wikidata-p1082", "wikimedia-wikidata", "population", null, 1440000L,
                source, EnrichmentProposal.ReviewAction.FILL_IF_EMPTY);
        EnrichmentProvider provider = provider("Wikimedia", context ->
                new EnrichmentProposal(request.subject(),
                        List.of(identity), List.of(value), List.of()));

        // A single batch input pause is answered by accepting every proposal — no
        // per-member review is ever requested.
        int[] reviews = {0};
        ProcessInputHandler acceptAll = new ProcessInputHandler() {
            @Override public <T> T request(
                    ProcessInputRequest<T> req, CancellationToken cancellation) {
                reviews[0]++;
                FindDataBatchReviewRequest batch = (FindDataBatchReviewRequest) req;
                List<EnrichmentDecision> accepted = batch.proposals().stream()
                        .map(EnrichmentDecision::acceptDefault)
                        .filter(java.util.Objects::nonNull)
                        .toList();
                return req.responseType().cast(new BatchReviewDecision(accepted));
            }
        };

        ProcessOutcome<FindDataBatchResult> outcome = new ProcessRunner(
                new QueryContext(null, null), null, acceptAll)
                .run(new FindDataBatchProcess(List.of(
                                new FindDataProcess(request, List.of(provider), false)),
                                0, "population"),
                        new CancellationToken());

        assertEquals(ProcessStatus.SUCCEEDED, outcome.status());
        assertEquals(1, reviews[0]);   // exactly ONE review for the whole batch
        assertEquals(1, outcome.result().acceptedDecisions().size());
        EnrichmentDecision decision = outcome.result().acceptedDecisions().get(0);
        assertEquals("hawaii", decision.subject().targetId());
        assertEquals(1440000L, decision.fields().get(0).candidate().proposedValue());
    }

    private static EnrichmentProvider provider(
            String name, ThrowingDiscovery discovery) {
        return new EnrichmentProvider() {
            public String name() { return name; }
            public boolean supports(EnrichmentRequest request) { return true; }
            public Query<EnrichmentProposal> discover(EnrichmentRequest request) {
                return new Query<>() {
                    public String purpose() { return name; }
                    public String skeleton() { return ""; }
                    public Map<String, String> parameters() { return Map.of(); }
                    public EnrichmentProposal execute(QueryContext context) throws Exception {
                        return discovery.run(context);
                    }
                    public int rowCount(EnrichmentProposal result) {
                        return result.media().size();
                    }
                };
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingDiscovery {
        EnrichmentProposal run(QueryContext context) throws Exception;
    }
}
