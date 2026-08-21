package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;

import datasource.SourceRef;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FindDataProcessTest {

    @Test
    void skipsFallbackWhenPrimaryFindsAUsableFieldValue() {
        EnrichmentRequest request = populationRequest();
        AtomicInteger fallbackCalls = new AtomicInteger();
        EnrichmentProvider wikidata = provider("Wikidata", context ->
                fieldProposal(request, "Wikidata", "P1082", 10L, null));
        EnrichmentProvider dbpedia = provider("DBpedia", context -> {
            fallbackCalls.incrementAndGet();
            return fieldProposal(request, "DBpedia", "populationTotal", 11L, null);
        });

        ProcessOutcome<FindDataResult> outcome = run(request,
                                                     EnrichmentRoute.of(List.of(wikidata), List.of(dbpedia)));

        assertEquals(ProcessStatus.SUCCEEDED, outcome.status());
        assertEquals(0, fallbackCalls.get());
        assertEquals("Wikidata",
                     outcome.result().proposal().fields().get(0).source().kind());
    }

    @Test
    void consultsFallbackWhenPrimaryFindsNoValue() {
        EnrichmentRequest request = populationRequest();
        AtomicInteger fallbackCalls = new AtomicInteger();
        EnrichmentProvider wikidata = provider("Wikidata", context ->
                new EnrichmentProposal(request.subject(), List.of(), List.of(), List.of()));
        EnrichmentProvider dbpedia = provider("DBpedia", context -> {
            fallbackCalls.incrementAndGet();
            return fieldProposal(request, "DBpedia", "populationTotal", 11L, null);
        });

        ProcessOutcome<FindDataResult> outcome = run(request,
                                                     EnrichmentRoute.of(List.of(wikidata), List.of(dbpedia)));

        assertEquals(ProcessStatus.SUCCEEDED, outcome.status());
        assertEquals(1, fallbackCalls.get());
        assertEquals(11L, outcome.result().proposal().fields().get(0).proposedValue());
        assertEquals("DBpedia",
                     outcome.result().proposal().fields().get(0).source().kind());
    }

    @Test
    void incompatiblePrimaryValueDoesNotBlockFallback() {
        EnrichmentRequest request = populationRequest();
        EnrichmentProvider wikidata = provider("Wikidata", context ->
                fieldProposal(request, "Wikidata", "P1082", "not numeric",
                              "String is incompatible with population"));
        EnrichmentProvider dbpedia = provider("DBpedia", context ->
                fieldProposal(request, "DBpedia", "populationTotal", 11L, null));

        ProcessOutcome<FindDataResult> outcome = run(request,
                                                     EnrichmentRoute.of(List.of(wikidata), List.of(dbpedia)));

        assertEquals(ProcessStatus.SUCCEEDED, outcome.status());
        assertEquals(2, outcome.result().proposal().fields().size());
        assertFalse(outcome.result().proposal().fields().get(0).compatible());
        assertEquals("DBpedia",
                     outcome.result().proposal().fields().get(1).source().kind());
    }

    @Test
    void fallbackCanRecoverAValueAfterPrimaryFailure() {
        EnrichmentRequest request = populationRequest();
        EnrichmentProvider wikidata = provider("Wikidata", context -> {
            throw new IllegalStateException("Wikidata unavailable");
        });
        EnrichmentProvider dbpedia = provider("DBpedia", context ->
                fieldProposal(request, "DBpedia", "populationTotal", 11L, null));

        ProcessOutcome<FindDataResult> outcome = run(request,
                                                     EnrichmentRoute.of(List.of(wikidata), List.of(dbpedia)));

        assertEquals(ProcessStatus.PARTIAL, outcome.status());
        assertNotNull(outcome.result());
        assertEquals(11L, outcome.result().proposal().fields().get(0).proposedValue());
    }

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
                        new SourceRef("Test", "1", "https://example.test/1"),
                        "test", 1.0, "", "", false);
        EnrichmentProposal.IdentityCandidate identity =
                new EnrichmentProposal.IdentityCandidate(
                        "identity", "One", List.of(), "",
                        candidate.source(), 1.0, List.of());

        EnrichmentProvider succeeds = provider("Good", context ->
                new EnrichmentProposal(request.subject(), List.of(identity),
                        List.of(), List.of(candidate)));
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
        SourceRef source =
                new SourceRef("Wikidata", "Q782", "url");
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
        assertEquals(0, reviews[0]);   // execution never opens UI; host reviews afterwards
        assertEquals(1, outcome.result().results().size());
        EnrichmentDecision decision = EnrichmentDecision.acceptDefault(
                outcome.result().results().get(0).proposal());
        assertEquals("hawaii", decision.subject().targetId());
        assertEquals(1440000L, decision.fields().get(0).candidate().proposedValue());
    }

    @Test
    void corroborationDoesNotSuppressAValueProducingFallback() {
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", "local-1", "Q1", "One"),
                "population", false, List.of(), null, 10L);
        SourceRef evidenceSource = new SourceRef(
                "Wikipedia", "One", "https://example.test/One", "field:population");
        EnrichmentProposal.IdentityCandidate evidenceIdentity =
                new EnrichmentProposal.IdentityCandidate(
                        "wikipedia", "One", List.of(), "", evidenceSource, 1, List.of());
        EnrichmentProposal.FieldCandidate corroboration =
                new EnrichmentProposal.FieldCandidate(
                        "support", "wikipedia", "population", 10L, 10L,
                        evidenceSource, EnrichmentProposal.ReviewAction.CORROBORATE);
        int[] fallbackCalls = {0};
        EnrichmentProvider primary = provider("Evidence", context -> new EnrichmentProposal(
                request.subject(), List.of(evidenceIdentity),
                List.of(corroboration), List.of()));
        EnrichmentProvider fallback = provider("Fallback", context -> {
            fallbackCalls[0]++;
            return fieldProposal(request, "DBpedia", "population", 20L, null);
        });

        ProcessOutcome<FindDataResult> outcome = run(request,
                EnrichmentRoute.of(List.of(primary), List.of(fallback)));

        assertEquals(ProcessStatus.SUCCEEDED, outcome.status());
        assertEquals(1, fallbackCalls[0]);
        assertEquals(2, outcome.result().proposal().fields().size());
        assertTrue(outcome.result().proposal().hasUsableCandidate("population"));
        assertEquals(1, outcome.result().proposal().corroborationCount());
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

    private static EnrichmentRequest populationRequest() {
        return new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", "local-1", "Q1", "One"),
                "population", false, List.of());
    }

    private static EnrichmentProposal fieldProposal(
            EnrichmentRequest request,
            String sourceKind,
            String property,
            Object value,
            String incompatibility) {
        String identityId = sourceKind.toLowerCase();
        SourceRef source = new SourceRef(
                sourceKind, request.subject().id(), "url", property);
        EnrichmentProposal.IdentityCandidate identity =
                new EnrichmentProposal.IdentityCandidate(
                        identityId, request.subject().displayName(), List.of(), "",
                        source, 1.0, List.of());
        EnrichmentProposal.FieldCandidate field = new EnrichmentProposal.FieldCandidate(
                sourceKind + "-value", identityId, request.targetField(),
                request.currentValue(), value, source,
                incompatibility == null
                        ? EnrichmentProposal.ReviewAction.FILL_IF_EMPTY
                        : EnrichmentProposal.ReviewAction.IGNORE,
                incompatibility, false);
        return new EnrichmentProposal(
                request.subject(), List.of(identity), List.of(field), List.of());
    }

    private static ProcessOutcome<FindDataResult> run(
            EnrichmentRequest request, EnrichmentRoute route) {
        return new ProcessRunner(
                new QueryContext(null, null), null, ProcessInputHandler.unsupported())
                .run(new FindDataProcess(request, route, false), new CancellationToken());
    }

    @FunctionalInterface
    private interface ThrowingDiscovery {
        EnrichmentProposal run(QueryContext context) throws Exception;
    }
}
