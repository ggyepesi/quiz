package datasource.graph;

import datasource.EntityRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage is recorded against an EDGE, not against the way that edge is materialized.
 *
 * <p>A statement pattern reaches its target through a reified statement with a role
 * field at each end; a field step reaches it through a typed field on the source class.
 * The ledger's question — which nodes has this edge expanded, and which has it merely
 * reached — is the same either way, and answering it twice would be two ledgers that can
 * disagree about the same walk.
 */
class LedgerServesEitherEdgeTest {

    private static final GraphRelation RELATION = new GraphRelation("provider", "rel-1");

    private static GraphExpansionPattern pattern() {
        return new GraphExpansionPattern("Holding:rel-1:Position", "Person", "Position",
                RELATION, "Holding", "source", "position",
                GraphTraversalDirection.INCOMING);
    }

    private static GraphTraversalStep step() {
        return new GraphTraversalStep("Position.broader", "Position", "Position",
                "broader", RELATION, GraphTraversalDirection.OUTGOING,
                GraphExpansionPolicy.CURATED);
    }

    private static GraphExpansionCoverage covered(
            GraphEdgeDefinition edge, String id, GraphExpansionCoverage.State state) {
        return new GraphExpansionCoverage(edge.id(), new EntityRef("ns", id),
                edge.relation(), edge.direction(), state);
    }

    @Test void bothKindsOfEdgeAnswerTheContractTheLedgerAsks() {
        assertInstanceOf(GraphEdgeDefinition.class, pattern());
        assertInstanceOf(GraphEdgeDefinition.class, step());

        GraphEdgeDefinition edge = step();
        assertEquals("Position.broader", edge.id());
        assertEquals("Position", edge.sourceNodeClass());
        assertEquals("Position", edge.targetNodeClass());
        assertEquals(RELATION, edge.relation());
        assertEquals(GraphTraversalDirection.OUTGOING, edge.direction());
    }

    @Test void aFieldStepsFrontierIsReadTheSameWayAPatternsIs() {
        GraphDiscoveryState state = new GraphDiscoveryState(
                List.of(pattern()),
                List.of(covered(step(), "Q1", GraphExpansionCoverage.State.EXPANDED),
                        covered(step(), "Q2", GraphExpansionCoverage.State.ENCOUNTERED),
                        covered(pattern(), "Q3", GraphExpansionCoverage.State.ENCOUNTERED)));

        List<GraphExpansionCoverage> frontier = state.frontier(step());
        assertEquals(1, frontier.size());
        assertEquals("Q2", frontier.getFirst().node().id());

        assertEquals(1, state.coverage(
                step(), GraphExpansionCoverage.State.EXPANDED).size());
        assertEquals(1, state.frontier(pattern()).size(),
                "the pattern's own coverage is untouched by the step sharing the ledger");
    }

    /**
     * Identity, relation and direction all have to agree. An edge that walks the same
     * relation the other way has not covered what the first one covered.
     */
    @Test void anEdgeDoesNotInheritCoverageFromTheOppositeDirection() {
        GraphTraversalStep outgoing = step();
        GraphTraversalStep incoming = new GraphTraversalStep(
                outgoing.id(), "Position", "Position", "broader", RELATION,
                GraphTraversalDirection.INCOMING, GraphExpansionPolicy.CURATED);

        GraphDiscoveryState state = new GraphDiscoveryState(List.of(),
                List.of(covered(outgoing, "Q1", GraphExpansionCoverage.State.ENCOUNTERED)));

        assertEquals(1, state.frontier(outgoing).size());
        assertEquals(0, state.frontier(incoming).size());
    }
}
