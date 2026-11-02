package wikidata.explore.generation;

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
                GraphApplication.message("PositionReplacementExpansion",
                        "PositionWithHolders", PopulationOperation.ADD,
                        385, 53, 438, 9, true));
    }

    @Test void narrowingApplyStillReportsThePopulationItKept() {
        assertEquals(
                "Applied graph result \"PositionFilter\": kept 357 accepted Position "
                        + "instance(s) from 1317 existing instance(s). Use \"Save domain\" "
                        + "to persist them.",
                GraphApplication.message("PositionFilter", "Position",
                        PopulationOperation.NARROW, 1317, 0, 357, 0, false));
    }

    /** Narrowing never acquires (#424): its accepted ids without an instance stay ids, so
     *  the message must not send the reader to a Generate that will not fetch them. */
    @Test void narrowingApplyDoesNotPromiseToAcquireMissingIds() {
        assertEquals(
                "Applied graph result \"GraphConstraint\": kept 1299 accepted Position "
                        + "instance(s) from 1882 existing instance(s), and 8 accepted id(s) "
                        + "have no generated instance; narrowing keeps instances and acquires "
                        + "none. Use \"Save model\" to persist them.",
                GraphApplication.message("GraphConstraint", "Position",
                        PopulationOperation.NARROW, 1882, 0, 1299, 8, true));
    }
}
