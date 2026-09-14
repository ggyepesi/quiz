package wikidata.explore.generation;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.InMemoryGraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.FakeWikidataSparqlClient;
import wikidata.api.WikidataApiClient;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Incoming configured edges use the same graph-store demand shape as local waves. */
class WikidataGraphAdjacencyAcquisitionTest {
    @TempDir Path cacheDirectory;

    @Test void aGraphAcquisitionNamesItsInitialRequestTotal() {
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                java.util.stream.IntStream.range(0, 101)
                        .mapToObj(i -> EntityRef.wikidata("Q" + (i + 1))).toList(),
                new GraphRelation("wikidata", "P1001"),
                GraphTraversalDirection.OUTGOING);

        assertEquals("Acquire P1001 out — 101 node(s), 3 initial request(s); "
                        + "adaptive splits may add requests",
                WikidataGraphAdjacencyAcquisition.acquisitionTitle(demand));
    }

    @Test void anUnavailableOutgoingEntityCannotBecomeComplete() throws Exception {
        EntityRef position = EntityRef.wikidata("Q4164871");
        GraphRelation jurisdiction = new GraphRelation("wikidata", "P1001");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(position),
                jurisdiction, GraphTraversalDirection.OUTGOING);
        WikidataApiClient api = new WikidataApiClient("test") {
            @Override public PartialStatements getStatementsByPropertyPartial(
                    List<String> qids, List<String> pids, BatchLog log,
                    StatementBatchCommitter committer) {
                return new PartialStatements(Map.of(), 1, List.of("Q4164871"));
            }
        };
        InMemoryGraphStore store = new InMemoryGraphStore();

        new WikidataGraphAdjacencyAcquisition(api, null, null, null)
                .acquire(store, demand);

        assertEquals(GraphAdjacencyCoverage.UNAVAILABLE,
                store.adjacencyKnowledge(position, demand));
    }

    @Test void everyCompletedOutgoingBatchIsUsableAfterRestart() throws Exception {
        EntityRef position = EntityRef.wikidata("Q4164871");
        GraphRelation jurisdiction = new GraphRelation("wikidata", "P1001");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(position),
                jurisdiction, GraphTraversalDirection.OUTGOING);
        WikidataApiClient api = new WikidataApiClient("test") {
            @Override public PartialStatements getStatementsByPropertyPartial(
                    List<String> qids, List<String> pids, BatchLog log,
                    StatementBatchCommitter committer) throws Exception {
                Map<String, Map<String, List<ApiStatement>>> answer = Map.of(
                        "P1001", Map.of("Q4164871", List.of(new ApiStatement(
                                "Q4164871$P1001", "Q171150", Map.of()))));
                committer.commit(qids, answer);
                return new PartialStatements(answer, 0, List.of());
            }
        };
        try (var store = new datasource.persistence.PersistentGraphStore(cacheDirectory)) {
            new WikidataGraphAdjacencyAcquisition(api, null, null, null)
                    .acquire(store, demand);
        }

        try (var restored = new datasource.persistence.PersistentGraphStore(cacheDirectory)) {
            assertEquals(List.of(EntityRef.wikidata("Q171150")),
                    restored.adjacent(demand).edges().stream()
                            .map(edge -> (EntityRef) edge.target()).toList());
            assertTrue(restored.adjacent(demand).missingNodes().isEmpty());
        }
    }

    @Test void compatibleOutgoingRelationsShareOneEntityPassAndKeepSeparateCoverage()
            throws Exception {
        EntityRef position = EntityRef.wikidata("Q6412254");
        GraphRelation jurisdiction = new GraphRelation("wikidata", "P1001");
        GraphRelation country = new GraphRelation("wikidata", "P17");
        GraphAdjacencyDemand jurisdictionDemand = new GraphAdjacencyDemand(
                List.of(position), jurisdiction, GraphTraversalDirection.OUTGOING);
        GraphAdjacencyDemand countryDemand = new GraphAdjacencyDemand(
                List.of(position), country, GraphTraversalDirection.OUTGOING);
        List<List<String>> requestedProperties = new ArrayList<>();
        List<String> logMessages = new ArrayList<>();
        WikidataApiClient api = new WikidataApiClient("test") {
            @Override public PartialStatements getStatementsByPropertyPartial(
                    List<String> qids, List<String> pids, BatchLog log,
                    StatementBatchCommitter committer) throws Exception {
                requestedProperties.add(List.copyOf(pids));
                Map<String, Map<String, List<ApiStatement>>> answer = Map.of(
                        "P1001", Map.of("Q6412254", List.of(new ApiStatement(
                                "Q6412254$P1001", "Q171150", Map.of()))),
                        "P17", Map.of("Q6412254", List.of(new ApiStatement(
                                "Q6412254$P17", "Q28", Map.of()))));
                committer.commit(qids, answer);
                return new PartialStatements(answer, 0, List.of());
            }
        };

        try (var store = new datasource.persistence.PersistentGraphStore(cacheDirectory)) {
            new WikidataGraphAdjacencyAcquisition(api, null,
                    wikidata.explore.extract.GenerationLog.of(logMessages::add), null)
                    .acquireAll(store, List.of(jurisdictionDemand, countryDemand));
        }

        try (var restored = new datasource.persistence.PersistentGraphStore(cacheDirectory)) {
            assertEquals(List.of(List.of("P1001", "P17")), requestedProperties);
            assertEquals(List.of("Acquire P1001 + P17 out — 1 node(s), "
                    + "1 initial request(s); adaptive splits may add requests\n"),
                    logMessages);
            assertEquals(List.of(EntityRef.wikidata("Q171150")), restored
                    .adjacent(jurisdictionDemand).edges().stream()
                    .map(edge -> (EntityRef) edge.target()).toList());
            assertEquals(List.of(EntityRef.wikidata("Q28")), restored
                    .adjacent(countryDemand).edges().stream()
                    .map(edge -> (EntityRef) edge.target()).toList());
            assertTrue(restored.adjacent(jurisdictionDemand).missingNodes().isEmpty());
            assertTrue(restored.adjacent(countryDemand).missingNodes().isEmpty());
        }
    }

    @Test void partiallyCachedRelationsRequestEachNodeOnceForOnlyItsMissingProperties()
            throws Exception {
        EntityRef first = EntityRef.wikidata("Q1");
        EntityRef second = EntityRef.wikidata("Q2");
        GraphRelation jurisdiction = new GraphRelation("wikidata", "P1001");
        GraphRelation country = new GraphRelation("wikidata", "P17");
        List<String> calls = new ArrayList<>();
        WikidataApiClient api = new WikidataApiClient("test") {
            @Override public PartialStatements getStatementsByPropertyPartial(
                    List<String> qids, List<String> pids, BatchLog log,
                    StatementBatchCommitter committer) throws Exception {
                calls.add(String.join("+", qids) + ":" + String.join("+", pids));
                committer.commit(qids, Map.of());
                return new PartialStatements(Map.of(), 0, List.of());
            }
        };
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphAdjacencyDemand jurisdictionMissing = new GraphAdjacencyDemand(
                List.of(first), jurisdiction, GraphTraversalDirection.OUTGOING);
        GraphAdjacencyDemand countryMissing = new GraphAdjacencyDemand(
                List.of(first, second), country, GraphTraversalDirection.OUTGOING);

        new WikidataGraphAdjacencyAcquisition(api, null, null, null)
                .acquireAll(store, List.of(jurisdictionMissing, countryMissing));

        assertEquals(List.of("Q1:P1001+P17", "Q2:P17"), calls);
        assertTrue(store.adjacent(jurisdictionMissing).missingNodes().isEmpty());
        assertTrue(store.adjacent(countryMissing).missingNodes().isEmpty());
    }

    @Test void aRelationThisProviderCannotFetchIsRefusedWithoutAnyClient() {
        // The executor asks the provider, so the provider has to answer. Refusing with
        // neither an API nor a SPARQL client configured is the point: validation
        // happens before anything is reachable to spend.
        GraphRelation foreign = new GraphRelation("dbpedia", "birthPlace");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new WikidataGraphAdjacencyAcquisition(null, null, null, null)
                        .validateRelations(List.of(
                                new GraphRelation("wikidata", "P279"), foreign)));

        assertTrue(refused.getMessage().contains("requires a Wikidata property relation"),
                refused.getMessage());
    }

    @Test void anUnpagedIncomingAnswerIsNeverClaimedComplete()
            throws Exception {
        EntityRef position = EntityRef.wikidata("Q4164871");
        GraphRelation subclass = new GraphRelation("wikidata", "P279");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(position),
                subclass, GraphTraversalDirection.INCOMING);
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient()
                .row(Map.of("subject", "Q6412254", "value", "Q4164871"));
        InMemoryGraphStore store = new InMemoryGraphStore();

        new WikidataGraphAdjacencyAcquisition(null, sparql, null, null)
                .acquire(store, demand);

        assertEquals(List.of(EntityRef.wikidata("Q6412254")), store.adjacent(demand)
                .edges().stream().map(edge -> edge.source()).toList());
        assertEquals(GraphAdjacencyCoverage.INCOMPLETE,
                store.adjacencyKnowledge(position, demand));
        assertTrue(WikidataGraphAdjacencyAcquisition.incomingQuery(
                subclass, List.of(position)).contains("?subject wdt:P279 ?value"));
    }
}
