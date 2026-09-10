package datasource.graph.constraint;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.GraphEdge;
import datasource.graph.store.InMemoryGraphStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Coverage-aware predicates are one shared decision path for every graph consumer. */
class GraphNodeConditionsTest {
    private static final GraphRelation RELATION =
            new GraphRelation("catalogue", "relation");

    @Test void oneKnownEdgeDisprovesAbsenceEvenWhenAdjacencyIsIncomplete() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        EntityRef source = entity("source");
        store.addEdges(List.of(new GraphEdge(
                source, RELATION, entity("known-target"), "test")));
        store.markCoverage(new GraphAdjacencyDemand(
                        List.of(source), RELATION, GraphTraversalDirection.OUTGOING),
                GraphAdjacencyCoverage.INCOMPLETE);

        var result = GraphNodeConditions.evaluate(store, source,
                new GraphRelationAbsent(RELATION, GraphTraversalDirection.OUTGOING));

        assertEquals(GraphNodeConditions.Decision.NOT_MATCHED, result.decision());
    }

    @Test void reachesUsesTheConfiguredTraversalDirection() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        EntityRef source = entity("source");
        EntityRef target = entity("target");
        GraphEdge edge = new GraphEdge(source, RELATION, target, "test");
        store.addEdges(List.of(edge));
        store.markCoverage(new GraphAdjacencyDemand(
                        List.of(target), RELATION, GraphTraversalDirection.INCOMING),
                GraphAdjacencyCoverage.COMPLETE);

        var result = GraphNodeConditions.evaluate(store, target,
                new GraphRelationReaches(
                        RELATION, GraphTraversalDirection.INCOMING, source));

        assertEquals(GraphNodeConditions.Decision.MATCHED, result.decision());
        assertEquals(List.of(edge), result.observedEdges());
        assertEquals(List.of(edge), result.witnessEdges());
    }

    private static EntityRef entity(String id) {
        return new EntityRef("catalogue", id);
    }
}
