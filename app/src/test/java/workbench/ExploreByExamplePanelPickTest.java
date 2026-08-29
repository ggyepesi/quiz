package workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.query.logical.DiscoverEntityRelationQuery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The picker emits the visibly-selected candidate, not a stale explored entity. */
class ExploreByExamplePanelPickTest {

    @Test void selectedCandidateWinsOverExploredEntity() {
        // Explored A, but candidate B is selected → B must win (the bug emitted A).
        assertArrayEquals(new String[]{"Q2", "B"},
                ExploreByExamplePanel.pickEntity(true, "Q2", "B", "Q1", "A"));
    }

    @Test void exploredEntityIsTheFallbackWithoutACandidate() {
        assertArrayEquals(new String[]{"Q1", "A"},
                ExploreByExamplePanel.pickEntity(false, "", "", "Q1", "A"));
    }

    @Test void anInvalidCandidateFallsBackToTheExploredEntity() {
        assertArrayEquals(new String[]{"Q1", "A"},
                ExploreByExamplePanel.pickEntity(true, "", "", "Q1", "A"));
    }

    @Test void nothingSelectedNorExplored() {
        assertNull(ExploreByExamplePanel.pickEntity(false, "", "", "", ""));
    }

    @Test void relationDiscoveryUsesTheExploredEntityRatherThanTheExample() {
        RelationView outgoing = new RelationView(
                "→", "P39", "position held", 3,
                "King", "entity", "Q116");

        ExploreByExamplePanel.RelationExploration request =
                ExploreByExamplePanel.relationExploration(outgoing, "Q42");

        assertEquals("P39", request.pid());
        assertEquals("Q42", request.startingQid());
        assertEquals(DiscoverEntityRelationQuery.Direction.OUTGOING,
                request.direction());
    }

    @Test void relationDiscoveryPreservesAnIncomingEdgeDirection() {
        RelationView incoming = new RelationView(
                "←", "P39", "position held", 3,
                "Holder", "entity", "Q116");

        assertEquals(DiscoverEntityRelationQuery.Direction.INCOMING,
                ExploreByExamplePanel.relationExploration(incoming, "Q42").direction());
    }
}
