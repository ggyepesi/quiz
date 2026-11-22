package domain.datasource.graph.constraint;

import domain.datasource.EntityRef;
import domain.datasource.graph.GraphRelation;
import domain.datasource.graph.GraphTraversalDirection;
import domain.datasource.graph.store.GraphAdjacencyCoverage;
import domain.datasource.graph.store.GraphAdjacencyDemand;
import domain.datasource.graph.store.GraphEdge;
import domain.datasource.graph.store.InMemoryGraphStore;
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

    @Test void nodeIsUnderAcceptsTheNamedNodeAndItsDescendants() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        EntityRef target = entity("group-of-characters");
        EntityRef descendant = entity("deity-group");
        GraphRelation subclass = new GraphRelation("catalogue", "subclass");
        store.addEdges(List.of(new GraphEdge(
                descendant, subclass, target, "test")));
        store.markCoverage(new GraphAdjacencyDemand(
                        List.of(descendant), subclass, GraphTraversalDirection.OUTGOING),
                GraphAdjacencyCoverage.COMPLETE);

        GraphNodeIsUnder condition = GraphNodeIsUnder.of(target, subclass);

        assertEquals(GraphNodeConditions.Decision.MATCHED,
                GraphNodeConditions.evaluate(store, target, condition).decision(),
                "the named type itself must not need a self-edge");
        assertEquals(GraphNodeConditions.Decision.MATCHED,
                GraphNodeConditions.evaluate(store, descendant, condition).decision());
    }

    @Test void nodeIsUnderReviewsUnknownHierarchyAndRejectsKnownNonMatch() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        EntityRef target = entity("group-of-characters");
        EntityRef unknown = entity("unknown");
        EntityRef other = entity("other");
        GraphRelation subclass = new GraphRelation("catalogue", "subclass");
        store.markCoverage(new GraphAdjacencyDemand(
                        List.of(unknown), subclass, GraphTraversalDirection.OUTGOING),
                GraphAdjacencyCoverage.UNAVAILABLE);
        store.markCoverage(new GraphAdjacencyDemand(
                        List.of(other), subclass, GraphTraversalDirection.OUTGOING),
                GraphAdjacencyCoverage.COMPLETE);

        GraphNodeIsUnder condition = GraphNodeIsUnder.of(target, subclass);

        assertEquals(GraphNodeConditions.Decision.REVIEW,
                GraphNodeConditions.evaluate(store, unknown, condition).decision());
        assertEquals(GraphNodeConditions.Decision.NOT_MATCHED,
                GraphNodeConditions.evaluate(store, other, condition).decision());
    }

    private static EntityRef entity(String id) {
        return new EntityRef("catalogue", id);
    }
}
