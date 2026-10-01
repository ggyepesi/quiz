package wikidata.explore.model;

import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphTraversalDirection;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** History keeps its one-hop boundary inspection explicit and does not misclassify it as output. */
class HistoryBoundaryDiscoveryConfigurationTest {

    @Test void peopleDiscoverTheirPositionsOutsideTheImportedPopulation() throws Exception {
        GeneratedProjectModel history = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/history/history.model.json"));

        GeneratedClassModel reachable = history.findClass("ReachablePosition");
        assertNotNull(reachable);
        assertEquals("Position", reachable.baseClassName());

        GraphClassSource source = history.findClass("PersonPositionBoundaryDiscovery")
                .graphSource();
        assertEquals("Person", source.startNode().qidSourceClass());
        GraphDiscoveryConfiguration.NextNode position = source.nextNodes().getFirst();
        assertEquals("P39", position.property().relationId());
        assertEquals(GraphTraversalDirection.OUTGOING, position.directionFromPrevious());
        assertEquals("Position", position.populationClass(),
                "inside-population Positions are the accepted output until outside-output exists");
        assertEquals("PositionWithHoldersPopulation",
                position.admissionPopulationSelection());
        assertEquals(GraphDiscoveryConfiguration.PopulationOperation.ADD,
                position.populationOperation());
    }
}
