package wikidata.explore.generation;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.InMemoryGraphStore;
import org.junit.jupiter.api.Test;
import wikidata.FakeWikidataSparqlClient;
import wikidata.api.WikidataApiClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Incoming configured edges use the same graph-store demand shape as local waves. */
class WikidataGraphAdjacencyAcquisitionTest {
    @Test void anUnavailableOutgoingEntityCannotBecomeComplete() throws Exception {
        EntityRef position = EntityRef.wikidata("Q4164871");
        GraphRelation jurisdiction = new GraphRelation("wikidata", "P1001");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(position),
                jurisdiction, GraphTraversalDirection.OUTGOING);
        WikidataApiClient api = new WikidataApiClient("test") {
            @Override public PartialStatements getStatementsByPropertyPartial(
                    List<String> qids, List<String> pids, BatchLog log) {
                return new PartialStatements(Map.of(), 1, List.of("Q4164871"));
            }
        };
        InMemoryGraphStore store = new InMemoryGraphStore();

        new WikidataGraphAdjacencyAcquisition(api, null, null, null)
                .acquire(store, demand);

        assertEquals(GraphAdjacencyCoverage.UNAVAILABLE,
                store.adjacencyKnowledge(position, demand));
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
