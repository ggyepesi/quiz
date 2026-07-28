package quiz.enrichment;

import org.junit.jupiter.api.Test;
import process.CancellationToken;
import process.ProcessInputHandler;
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
