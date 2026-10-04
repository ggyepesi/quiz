package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** History retains and classifies its boundary through ordinary generation. */
class HistoryBoundaryDiscoveryConfigurationTest {

    @Test void peopleDiscoverTheirPositionsOutsideTheImportedPopulation() throws Exception {
        GeneratedProjectModel history = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/history/history.model.json"));

        GeneratedClassModel reachable = history.findClass("ReachablePosition");
        assertNotNull(reachable);
        assertEquals("Position", reachable.baseClassName());
        assertEquals(SubclassCondition.Kind.OUTSIDE_POPULATION,
                reachable.subclassCondition().kind());
        assertEquals("PositionWithHoldersPopulation",
                reachable.subclassCondition().selectionName());

        GeneratedClassModel holding = history.findClass("OfficeHolding");
        assertEquals(EntityBound.Kind.UNBOUNDED,
                holding.statementSource().objectBound().kind(),
                "all positions on the discovered people are retained");
        assertEquals("PositionWithHoldersPopulation",
                holding.statementSource().discoveryObjectBound().selectionName(),
                "the imported position population still controls subject discovery");

        assertNull(history.findClass("PersonPositionBoundaryDiscovery"),
                "the reusable graph construct remains available, but History no longer needs one");
    }
}
