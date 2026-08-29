package graphview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphViewModelTest {
    @Test void modelOwnsStableCopiesOfProviderNeutralNodesAndEdges() {
        List<GraphViewModel.Node> nodes = new ArrayList<>();
        nodes.add(new GraphViewModel.Node("source-id", "Source label", null, 0,
                GraphViewModel.State.DEFAULT, java.util.Map.of(), new Object()));

        GraphViewModel model = new GraphViewModel(nodes, List.of());
        nodes.clear();

        assertEquals(1, model.nodes().size());
        assertEquals("source-id", model.nodes().getFirst().id());
        assertEquals(java.util.Map.of(), model.nodes().getFirst().details());
    }
}
