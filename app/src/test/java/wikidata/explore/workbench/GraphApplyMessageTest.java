package wikidata.explore.workbench;

import datasource.graph.GraphDiscoveryConfiguration.PopulationOperation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The graph Apply log states the operation that actually changed the class population. */
class GraphApplyMessageTest {

    @Test void additiveApplyReportsExistingAddedResultingAndUngeneratedCounts() {
        assertEquals(
                "Applied graph result \"PositionReplacementExpansion\": added 53 generated "
                        + "instance(s) to 385 existing PositionWithHolders instance(s); 438 "
                        + "instance(s) now belong to PositionWithHolders, and 9 accepted "
                        + "id(s) have no generated instance yet — generate to acquire them. "
                        + "Use \"Save model\" to persist them.",
                ModelBuilderFrame.graphApplyMessage("PositionReplacementExpansion",
                        "PositionWithHolders", PopulationOperation.ADD,
                        385, 53, 438, 9, true));
    }

    @Test void narrowingApplyStillReportsThePopulationItKept() {
        assertEquals(
                "Applied graph result \"PositionFilter\": kept 357 accepted Position "
                        + "instance(s) from 1317 existing instance(s). Use \"Save domain\" "
                        + "to persist them.",
                ModelBuilderFrame.graphApplyMessage("PositionFilter", "Position",
                        PopulationOperation.NARROW, 1317, 0, 357, 0, false));
    }
}
