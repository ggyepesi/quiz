package batch;

import org.junit.jupiter.api.Test;
import process.CancellationToken;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The executor reports the request a unit ISSUES, not the key that identifies it.
 *
 * <p>The two are different kinds of string and only one of them is a query. A unit's key
 * is deliberately ugly — {@code "Residual fields (+2)#-308638713@100"} — because the
 * executor rejects duplicate keys and a collision must fail loudly. Reporting it in the
 * request slot made the log render it as the query, so every batched stage offered a
 * WDQS link that opened a checkpoint key.
 *
 * <p>Nothing fails when this is wrong: the run completes, the log is populated, and only
 * a human following the link finds out. So the reported value has to be asserted.
 */
class BatchProgressRequestTest {

    /** A unit whose request text is plainly distinguishable from its key. */
    private record QueryUnit(String key, String request) implements WorkUnit<String> {
        @Override public WorkDescriptor descriptor() {
            return new WorkDescriptor("test.query", key, "Batch " + key, Map.of());
        }
        @Override public String execute() { return request; }
        @Override public List<WorkUnit<String>> split() { return List.of(); }
    }

    /** A unit that issues no request — a pure computation. */
    private record ComputeUnit(String key) implements WorkUnit<String> {
        @Override public WorkDescriptor descriptor() {
            return new WorkDescriptor("test.compute", key, "Compute " + key, Map.of());
        }
        @Override public String execute() { return key; }
        @Override public List<WorkUnit<String>> split() { return List.of(); }
    }

    private static void run(WorkUnit<String> unit, List<String> reported) throws Exception {
        BatchProgress progress = (title, request) -> {
            reported.add(request);
            return new BatchProgress.Running() {
                @Override public void done(String summary) { }
                @Override public void failed(String error) { }
            };
        };
        new BatchExecutor<String>(
                BatchPolicy.defaults(), progress,
                error -> FailureDecision.of(BatchFailure.FATAL),
                new CancellationToken(), BatchCheckpointStore.NONE)
                .run(List.of(unit), (descriptor, value) -> { });
    }

    @Test void theReportedRequestIsTheQueryTheUnitIssues() throws Exception {
        java.util.List<String> reported = new java.util.ArrayList<>();
        String sparql = "SELECT ?value WHERE { ?value wdt:P31 wd:Q11424 }";

        run(new QueryUnit("Residual fields (+2)#-308638713@100", sparql), reported);

        assertEquals(List.of(sparql), reported,
                     "the log links this text as the query — a key here is not a query");
    }

    @Test void aUnitWithNoRequestReportsNothingRatherThanItsKey() throws Exception {
        java.util.List<String> reported = new java.util.ArrayList<>();

        run(new ComputeUnit("compute#1"), reported);

        assertEquals(1, reported.size());
        assertTrue(reported.getFirst().isBlank(),
                   "a log shows no request rather than a fabricated one, got: "
                           + reported.getFirst());
    }
}
