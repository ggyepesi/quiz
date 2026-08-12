package wikidata.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One policy boundary for entity names, with two ways of running.
 *
 * <p>Generation and interactive repair need the same semantics — English with a
 * multilingual fallback, explicit missing entities, partial results kept — and differ
 * only in how hard they may push. Generation fans out over the client's bounded pool; a
 * repair the user clicked runs sequentially so one click cannot become a burst.
 *
 * <p>What matters in both is that a refused batch costs only its own labels. That is what
 * kept 2,700 references alive when 54 batches were throttled mid-run, and it is easy to
 * refactor away because nothing about a partial result looks wrong.
 */
class WikidataEntityLabelResolverTest {

    /** Records the batches it was asked for; refuses any containing a given QID. */
    private static class FakeClient extends WikidataApiClient {
        final List<List<String>> batches = new ArrayList<>();
        private final String refuse;
        private final Set<String> missing;

        FakeClient(String refuse, Set<String> missing) {
            super("test");
            this.refuse = refuse;
            this.missing = missing;
        }

        @Override public Map<String, ApiEntity> getEntities(
                List<String> qids, List<String> claimPids, BatchLog log) throws Exception {
            batches.add(List.copyOf(qids));
            if (refuse != null && qids.contains(refuse)) {
                throw new java.io.IOException("HTTP 429");
            }
            Map<String, ApiEntity> out = new LinkedHashMap<>();
            for (String qid : qids) {
                out.put(qid, missing.contains(qid)
                        ? new ApiEntity(qid, "", Map.of(), true)
                        : new ApiEntity(qid, "Label " + qid, Map.of()));
            }
            return out;
        }

        @Override public PartialEntities getEntitiesBestEffort(
                List<String> qids, List<String> claimPids, BatchLog log) throws Exception {
            batches.add(List.copyOf(qids));
            return new PartialEntities(getEntitiesUnchecked(qids), 0);
        }

        private Map<String, ApiEntity> getEntitiesUnchecked(List<String> qids) {
            Map<String, ApiEntity> out = new LinkedHashMap<>();
            for (String qid : qids) out.put(qid, new ApiEntity(qid, "Label " + qid, Map.of()));
            return out;
        }
    }

    private static List<String> qids(int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i <= to; i++) out.add("Q" + i);
        return out;
    }

    @Test void sequentialSendsFiftyAtATimeAndOneRefusalCostsOnlyItsOwnBatch()
            throws Exception {
        FakeClient client = new FakeClient("Q51", Set.of());

        WikidataEntityLabelResolver.Result result =
                new WikidataEntityLabelResolver(client).resolve(
                        qids(1, 150),
                        WikidataEntityLabelResolver.Execution.SEQUENTIAL, null);

        assertEquals(3, client.batches.size(), "150 QIDs is three requests of 50");
        assertEquals(50, client.batches.get(0).size());
        assertEquals(1, result.failedBatches());
        assertEquals(100, result.labels().size(),
                     "the two batches that succeeded keep their labels");
        assertTrue(result.labels().containsKey("Q1"));
        assertTrue(result.labels().containsKey("Q150"));
        assertTrue(!result.labels().containsKey("Q51"));
    }

    /** The parallel mode is the client's own best-effort call — ONE call, so the batches
     *  fan out; splitting it here would serialise generation's label pass. */
    @Test void boundedParallelIssuesASingleBestEffortCall() throws Exception {
        FakeClient client = new FakeClient(null, Set.of());

        WikidataEntityLabelResolver.Result result =
                new WikidataEntityLabelResolver(client).resolve(
                        qids(1, 150),
                        WikidataEntityLabelResolver.Execution.BOUNDED_PARALLEL, null);

        assertEquals(1, client.batches.size(), "the client does its own batching");
        assertEquals(150, client.batches.get(0).size());
        assertEquals(150, result.labels().size());
    }

    /** A deleted entity is reported as missing rather than labelled — a label equal to
     *  nothing would otherwise be indistinguishable from a lookup that failed. */
    @Test void aMissingEntityIsReportedSeparatelyFromALabel() throws Exception {
        FakeClient client = new FakeClient(null, Set.of("Q2"));

        WikidataEntityLabelResolver.Result result =
                new WikidataEntityLabelResolver(client).resolve(
                        List.of("Q1", "Q2"),
                        WikidataEntityLabelResolver.Execution.SEQUENTIAL, null);

        assertEquals(Set.of("Q2"), result.missing());
        assertEquals(Set.of("Q1"), result.labels().keySet());
    }

    @Test void nonQidsAndDuplicatesNeverReachTheApi() throws Exception {
        FakeClient client = new FakeClient(null, Set.of());

        new WikidataEntityLabelResolver(client).resolve(
                java.util.Arrays.asList("Q1", "Q1", "P31", null, "not-a-qid"),
                WikidataEntityLabelResolver.Execution.SEQUENTIAL, null);

        assertEquals(List.of(List.of("Q1")), client.batches);
    }

    @Test void nothingToResolveIssuesNoRequest() throws Exception {
        FakeClient client = new FakeClient(null, Set.of());

        WikidataEntityLabelResolver.Result result =
                new WikidataEntityLabelResolver(client).resolve(
                        List.of(), WikidataEntityLabelResolver.Execution.SEQUENTIAL, null);

        assertTrue(client.batches.isEmpty());
        assertEquals(0, result.failedBatches());
        assertTrue(result.labels().isEmpty());
    }

    /** Cancellation is not a partial result: an interrupted repair must stop, not report
     *  the batches it happened to finish as though the rest simply failed. */
    @Test void interruptionPropagatesRatherThanCountingAsAFailedBatch() {
        WikidataApiClient interrupting = new WikidataApiClient("test") {
            @Override public Map<String, ApiEntity> getEntities(
                    List<String> qids, List<String> claimPids, BatchLog log)
                    throws Exception {
                throw new InterruptedException("cancelled");
            }
        };

        assertThrows(InterruptedException.class,
                     () -> new WikidataEntityLabelResolver(interrupting).resolve(
                             List.of("Q1"),
                             WikidataEntityLabelResolver.Execution.SEQUENTIAL, null));
    }
}
