package datasource.graph.execution;

import datasource.EntityRef;
import datasource.graph.*;
import datasource.graph.store.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphWaveTest {
    private static final GraphRelation P39 = new GraphRelation("wikidata", "P39");

    @Test void distinguishesUnknownAdjacencyFromACompleteEmptyAnswer() {
        EntityRef position = EntityRef.wikidata("Q18341329");
        GraphTraversalStep holders = new GraphTraversalStep("holders", "Position", "Person",
                "holders", P39, GraphTraversalDirection.INCOMING,
                GraphExpansionPolicy.CURATED);
        InMemoryGraphStore store = new InMemoryGraphStore();

        GraphWaveResult unknown = GraphWave.evaluate(store, holders, List.of(position));
        assertTrue(unknown.requiresAcquisition());

        GraphAdjacencyDemand demand = unknown.missingDemand();
        store.markCoverage(demand, GraphAdjacencyCoverage.COMPLETE);
        GraphWaveResult knownEmpty = GraphWave.evaluate(store, holders, List.of(position));
        assertTrue(knownEmpty.completeLocally());
        assertTrue(knownEmpty.reached().isEmpty());
    }

    @Test void resumesTheSameWaveAfterAcquiredEdgesAreCommitted() {
        EntityRef position = EntityRef.wikidata("Q18341329");
        EntityRef holder = EntityRef.wikidata("Q123");
        GraphTraversalStep holders = new GraphTraversalStep("holders", "Position", "Person",
                "holders", P39, GraphTraversalDirection.INCOMING,
                GraphExpansionPolicy.CURATED);
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphAdjacencyDemand missing = GraphWave
                .evaluate(store, holders, List.of(position)).missingDemand();

        store.addEdges(List.of(new GraphEdge(holder, P39, position, "statement:1")));
        store.markCoverage(missing, GraphAdjacencyCoverage.COMPLETE);

        GraphWaveResult resumed = GraphWave.evaluate(store, holders, List.of(position));
        assertTrue(resumed.completeLocally());
        assertEquals(List.of(holder), resumed.reached());
        assertEquals("statement:1", resumed.edges().getFirst().provenanceId());
    }

    @Test void unavailableAdjacencyIsNotReturnedToTheOrdinaryAcquisitionBudget() {
        EntityRef position = EntityRef.wikidata("Q18341329");
        GraphTraversalStep holders = new GraphTraversalStep("holders", "Position", "Person",
                "holders", P39, GraphTraversalDirection.INCOMING,
                GraphExpansionPolicy.CURATED);
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                List.of(position), P39, GraphTraversalDirection.INCOMING);
        store.markCoverage(demand, GraphAdjacencyCoverage.UNAVAILABLE);

        GraphWaveResult result = GraphWave.evaluate(store, holders, List.of(position));
        assertFalse(result.requiresAcquisition());
        assertEquals(List.of(position), result.unavailable());
        assertFalse(result.completeLocally());
    }

    @Test void incompleteAdjacencyIsNotReturnedAsUnknownWork() {
        EntityRef position = EntityRef.wikidata("Q18341329");
        GraphTraversalStep holders = new GraphTraversalStep("holders", "Position", "Person",
                "holders", P39, GraphTraversalDirection.INCOMING,
                GraphExpansionPolicy.CURATED);
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                List.of(position), P39, GraphTraversalDirection.INCOMING);
        store.markCoverage(demand, GraphAdjacencyCoverage.INCOMPLETE);

        GraphWaveResult result = GraphWave.evaluate(store, holders, List.of(position));
        assertFalse(result.requiresAcquisition());
        assertFalse(result.completeLocally());
        assertEquals(List.of(position), result.incomplete());
        assertTrue(result.unavailable().isEmpty());
    }
}
