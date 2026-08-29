package graphview;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class InteractiveGraphViewTest {
    @Test void graphStreamRendererAcceptsAnIncrementalModelInSwing() {
        assertDoesNotThrow(() -> SwingUtilities.invokeAndWait(() -> {
            try (InteractiveGraphView view = new InteractiveGraphView()) {
                view.model(new GraphViewModel(
                        List.of(new GraphViewModel.Node("one", "One", null, 0,
                                        GraphViewModel.State.EXPANDED, java.util.Map.of(), new Object()),
                                new GraphViewModel.Node("two", "Two", null, 1,
                                        GraphViewModel.State.FRONTIER, java.util.Map.of(), new Object())),
                        List.of(new GraphViewModel.Edge("edge", "one", "two",
                                "relation", true))));
            }
        }));
    }
}
