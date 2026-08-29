package wikidata.explore.workbench;

import graphview.GraphViewModel;
import org.junit.jupiter.api.Test;
import wikidata.explore.query.logical.DiscoverEntityRelationQuery;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityRelationGraphModelTest {
    @Test void relationResultBecomesLinkedStatefulProviderNeutralGraph() {
        DiscoverEntityRelationQuery.Result result = new DiscoverEntityRelationQuery.Result(
                "P39", DiscoverEntityRelationQuery.Direction.INCOMING,
                List.of(new DiscoverEntityRelationQuery.Node("Q1", "root", 0),
                        new DiscoverEntityRelationQuery.Node("Q2", "child", 1)),
                List.of(new DiscoverEntityRelationQuery.Edge("Q2", "Q1")), false);

        GraphViewModel graph = EntityRelationDiscoveryPanel.graphModel(result);

        assertEquals(GraphViewModel.State.EXPANDED, graph.nodes().get(0).state());
        assertEquals(GraphViewModel.State.FRONTIER, graph.nodes().get(1).state());
        assertEquals("https://www.wikidata.org/wiki/Q2",
                graph.nodes().get(1).link().toString());
        assertEquals("P39", graph.edges().getFirst().label());
        assertTrue(graph.edges().getFirst().directed());
    }
}
