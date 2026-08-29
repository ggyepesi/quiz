package wikidata.explore.demo.closure;

import batch.BatchPolicy;
import batch.BatchProgress;
import batch.FailureClassifier;
import batch.InMemoryBatchCheckpointStore;
import org.junit.jupiter.api.Test;
import work.CancellationToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SharedPopulationClosureTest {

    @Test
    void defaultsToPositionAndMonarchTargetHierarchies() {
        SharedPopulationClosureConfig config = new SharedPopulationClosureConfig(
                "Q1", "P39", "P39", 1, 2, 20);

        assertEquals(List.of("Q4164871", "Q116"), config.targetRootQids());
        String query = SparqlSharedPopulationExpansionSource.typeQuery(
                List.of("Q2", "Q98964"), config.targetRootQids());
        assertTrue(query.contains("VALUES ?root { wd:Q4164871 wd:Q116 }"));
        assertTrue(query.contains("?target wdt:P279* ?root"));
        assertTrue(query.contains("?target wdt:P31/wdt:P279* ?root"));
        assertTrue(SparqlSharedPopulationExpansionSource.membershipQuery(
                config, List.of("Q2")).contains("LIMIT 2001"));
    }

    @Test
    void resumedWaveReducesResultsCommittedBeforeTheFailure() throws Exception {
        SharedPopulationClosureConfig config = new SharedPopulationClosureConfig(
                "Q1", "P39", "P39", 2, 1, 20);
        AtomicBoolean failQ3 = new AtomicBoolean(true);
        SharedPopulationExpansionSource source = (ignored, qids) -> {
            String qid = qids.get(0);
            if ("Q3".equals(qid) && failQ3.getAndSet(false)) {
                throw new IllegalStateException("simulated stop");
            }
            return switch (qid) {
                case "Q1" -> expansion(
                        edge("Q1", "One", "Q10", "A", "Q2", "Two"),
                        edge("Q1", "One", "Q11", "B", "Q3", "Three"));
                case "Q2" -> expansion(
                        edge("Q2", "Two", "Q12", "C", "Q4", "Four"));
                case "Q3" -> expansion(
                        edge("Q3", "Three", "Q13", "D", "Q5", "Five"));
                default -> SharedPopulationExpansion.empty();
            };
        };
        InMemoryBatchCheckpointStore journal = new InMemoryBatchCheckpointStore();
        SharedPopulationClosure closure = new SharedPopulationClosure(
                config, source, BatchPolicy.defaults(), BatchProgress.NOOP,
                FailureClassifier.standard(), new CancellationToken(), journal, 1);

        assertThrows(Exception.class, closure::execute);
        SharedPopulationClosure.Result resumed = closure.execute();

        assertEquals(List.of("Q1", "Q2", "Q3", "Q4", "Q5"),
                resumed.values().stream().map(SharedPopulationClosure.Value::qid).toList());
    }

    @Test
    void acquisitionLimitStopsFurtherWavesAndIsReportedAsIncomplete() throws Exception {
        SharedPopulationClosureConfig config = new SharedPopulationClosureConfig(
                "Q1", "P39", "P39", 4, 1, 20);
        SharedPopulationExpansionSource source = (ignored, qids) ->
                new SharedPopulationExpansion(
                        List.of(),
                        List.of(edge("Q1", "One", "Q10", "A", "Q2", "Two")),
                        0, true);
        SharedPopulationClosure closure = new SharedPopulationClosure(
                config, source, BatchPolicy.defaults(), BatchProgress.NOOP,
                FailureClassifier.standard(), new CancellationToken(),
                new InMemoryBatchCheckpointStore(), 1);

        SharedPopulationClosure.Result result = closure.execute();

        assertTrue(result.resourceLimitReached());
        assertEquals(1, result.waves().size());
        assertEquals(List.of("Q1", "Q2"), result.values().stream()
                .map(SharedPopulationClosure.Value::qid).toList());
    }

    private static SharedPopulationExpansion expansion(SharedPopulationEdge... edges) {
        return new SharedPopulationExpansion(List.of(), List.of(edges));
    }

    @Test
    void expandsOnlyNewValuesAndKeepsTheWitnessMember() throws Exception {
        SharedPopulationClosureConfig config = new SharedPopulationClosureConfig(
                "Q1", "P39", "P39", 4, 2, 20);
        Map<String, List<SharedPopulationEdge>> graph = Map.of(
                "Q1", List.of(edge("Q1", "Start", "Q10", "Alice", "Q2", "Second")),
                "Q2", List.of(
                        edge("Q2", "Second", "Q11", "Bob", "Q1", "Start"),
                        edge("Q2", "Second", "Q11", "Bob", "Q3", "Third")),
                "Q3", List.of());
        List<List<String>> calls = new ArrayList<>();
        SharedPopulationExpansionSource source = (ignored, qids) -> {
            calls.add(List.copyOf(qids));
            List<SharedPopulationEdge> edges = qids.stream()
                    .flatMap(qid -> graph.getOrDefault(qid, List.of()).stream()).toList();
            List<SharedPopulationMember> members = edges.stream()
                    .map(edge -> new SharedPopulationMember(
                            edge.sourceQid(), edge.sourceLabel(),
                            edge.memberQid(), edge.memberLabel()))
                    .toList();
            return new SharedPopulationExpansion(members, edges);
        };
        InMemoryBatchCheckpointStore journal = new InMemoryBatchCheckpointStore();
        SharedPopulationClosure closure = new SharedPopulationClosure(
                config, source, BatchPolicy.defaults(), BatchProgress.NOOP,
                FailureClassifier.standard(), new CancellationToken(), journal, 1);

        SharedPopulationClosure.Result result = closure.execute();

        assertEquals(List.of("Q1", "Q2", "Q3"), result.values().stream()
                .map(SharedPopulationClosure.Value::qid).toList());
        assertEquals(List.of(0, 1, 2), result.values().stream()
                .map(SharedPopulationClosure.Value::minimumDepth).toList());
        assertEquals(List.of(List.of("Q1"), List.of("Q2"), List.of("Q3")), calls);
        assertEquals("Bob", result.waves().get(1).edges().get(1).memberLabel());
        assertFalse(result.valueLimitReached());
        assertEquals(3, journal.events().stream()
                .filter(event -> event.type() == InMemoryBatchCheckpointStore.EventType.FINISH)
                .count());
    }

    private static SharedPopulationEdge edge(
            String from, String fromLabel,
            String member, String memberLabel,
            String to, String toLabel) {
        return new SharedPopulationEdge(
                from, fromLabel, member, memberLabel, to, toLabel);
    }
}
