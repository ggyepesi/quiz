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

class GraphEndpointConstraintsTest {
    private static final GraphRelation JURISDICTION =
            new GraphRelation("catalogue", "jurisdiction");
    private static final GraphRelation BROADER =
            new GraphRelation("catalogue", "broader");
    private static final GraphTraversalDirection OUT = GraphTraversalDirection.OUTGOING;

    private final EntityRef apostolicKing = entity("apostolic-king");
    private final EntityRef kingOfBohemia = entity("bohemian-king");
    private final EntityRef banOfCroatia = entity("croatian-ban");
    private final EntityRef hungary = entity("hungary");
    private final EntityRef croatia = entity("croatia");
    private final EntityRef king = entity("king");
    private final EntityRef ban = entity("ban");

    @Test void endpointsWithASharedDirectValueAreAccepted() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        edge(store, apostolicKing, JURISDICTION, hungary);
        edge(store, kingOfBohemia, JURISDICTION, hungary);
        covered(store, JURISDICTION, apostolicKing, kingOfBohemia);
        GraphEndpointPath jurisdiction = GraphEndpointPath.direct(JURISDICTION, OUT);

        var result = GraphEndpointConstraints.evaluate(store,
                intersects(jurisdiction, GraphEndpointConstraint.MissingPolicy.REVIEW),
                apostolicKing, kingOfBohemia);

        assertEquals(GraphEndpointConstraintResult.Decision.ACCEPTED, result.decision());
        assertEquals(java.util.Set.of(hungary), result.sharedValues());
    }

    @Test void coveredDifferentJurisdictionsAreRejected() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        edge(store, apostolicKing, JURISDICTION, hungary);
        edge(store, banOfCroatia, JURISDICTION, croatia);
        covered(store, JURISDICTION, apostolicKing, banOfCroatia);
        GraphEndpointPath jurisdiction = GraphEndpointPath.direct(JURISDICTION, OUT);

        var result = GraphEndpointConstraints.evaluate(store,
                intersects(jurisdiction, GraphEndpointConstraint.MissingPolicy.REVIEW),
                apostolicKing, banOfCroatia);

        assertEquals(GraphEndpointConstraintResult.Decision.REJECTED, result.decision());
    }

    @Test void nearestNodesWithoutJurisdictionFormTheComparableAbstraction() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        edge(store, apostolicKing, BROADER, kingOfBohemia);
        edge(store, kingOfBohemia, BROADER, king);
        edge(store, banOfCroatia, BROADER, ban);
        covered(store, BROADER, apostolicKing, kingOfBohemia, banOfCroatia, king, ban);
        edge(store, kingOfBohemia, JURISDICTION, hungary);
        covered(store, JURISDICTION, kingOfBohemia, king, ban);

        GraphEndpointPath abstraction = new GraphEndpointPath(
                BROADER, OUT, 3, GraphEndpointPath.Selection.NEAREST_MATCHING,
                new GraphRelationAbsent(JURISDICTION, OUT));

        var sameKind = GraphEndpointConstraints.evaluate(store,
                intersects(abstraction, GraphEndpointConstraint.MissingPolicy.REVIEW),
                apostolicKing, kingOfBohemia);
        var differentKind = GraphEndpointConstraints.evaluate(store,
                intersects(abstraction, GraphEndpointConstraint.MissingPolicy.REVIEW),
                apostolicKing, banOfCroatia);

        assertEquals(GraphEndpointConstraintResult.Decision.ACCEPTED, sameKind.decision());
        assertEquals(java.util.Set.of(king), sameKind.sharedValues());
        assertEquals(GraphEndpointConstraintResult.Decision.REJECTED,
                differentKind.decision());
    }

    @Test void unknownAbsenceIsReviewRatherThanInventedEvidence() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        edge(store, apostolicKing, BROADER, king);
        edge(store, kingOfBohemia, BROADER, king);
        covered(store, BROADER, apostolicKing, kingOfBohemia, king);
        GraphEndpointPath abstraction = new GraphEndpointPath(
                BROADER, OUT, 2, GraphEndpointPath.Selection.NEAREST_MATCHING,
                new GraphRelationAbsent(JURISDICTION, OUT));

        var result = GraphEndpointConstraints.evaluate(store,
                intersects(abstraction, GraphEndpointConstraint.MissingPolicy.REVIEW),
                apostolicKing, kingOfBohemia);

        assertEquals(GraphEndpointConstraintResult.Decision.REVIEW, result.decision());
    }

    @Test void aFartherSharedAncestorDoesNotHideAnUnknownNearerBoundary() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        EntityRef nearerLeft = entity("nearer-left");
        EntityRef nearerRight = entity("nearer-right");
        edge(store, apostolicKing, BROADER, nearerLeft);
        edge(store, kingOfBohemia, BROADER, nearerRight);
        edge(store, nearerLeft, BROADER, king);
        edge(store, nearerRight, BROADER, king);
        covered(store, BROADER, apostolicKing, kingOfBohemia,
                nearerLeft, nearerRight, king);
        covered(store, JURISDICTION, king); // nearer nodes deliberately unknown
        GraphEndpointPath abstraction = new GraphEndpointPath(
                BROADER, OUT, 2, GraphEndpointPath.Selection.NEAREST_MATCHING,
                new GraphRelationAbsent(JURISDICTION, OUT));

        var result = GraphEndpointConstraints.evaluate(store,
                intersects(abstraction, GraphEndpointConstraint.MissingPolicy.REVIEW),
                apostolicKing, kingOfBohemia);

        assertEquals(GraphEndpointConstraintResult.Decision.REVIEW, result.decision(),
                "the unknown nearer nodes may themselves be the configured boundary");
    }

    private static GraphEndpointConstraint intersects(
            GraphEndpointPath path, GraphEndpointConstraint.MissingPolicy missing) {
        return new GraphEndpointConstraint(path, path, missing);
    }

    private static EntityRef entity(String id) {
        return new EntityRef("catalogue", id);
    }

    private static void edge(InMemoryGraphStore store, EntityRef source,
            GraphRelation relation, EntityRef target) {
        store.addEdges(List.of(new GraphEdge(source, relation, target, "test")));
    }

    private static void covered(InMemoryGraphStore store, GraphRelation relation,
            EntityRef... nodes) {
        store.markCoverage(new GraphAdjacencyDemand(List.of(nodes), relation, OUT),
                GraphAdjacencyCoverage.COMPLETE);
    }
}
