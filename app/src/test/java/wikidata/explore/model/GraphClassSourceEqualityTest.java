package wikidata.explore.model;

import datasource.graph.GraphDiscoveryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GraphClassSourceEqualityTest {
    @Test void equalityIsTheValueOfTheGraphConfiguration() {
        var start = new GraphDiscoveryConfiguration.StartNode(
                "Position", "", GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY);
        GraphClassSource first = new GraphClassSource(start, List.of());
        GraphClassSource same = first.copy();
        GraphClassSource changed = new GraphClassSource(
                new GraphDiscoveryConfiguration.StartNode(
                        "Office", "", GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of());

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, changed);
    }
}
