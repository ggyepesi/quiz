package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.query.result.ClassSampleResult;

import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A class sample is presented by the Instances viewer, not by this panel.
 *
 * <p>It used to render its own QID/label table here, which made a sampled instance a
 * second presentation kind: the same object shown one way when generated and another
 * when sampled, with none of the nested-field expansion, links or view configuration
 * the ordinary result has. The panel now hands the result on and says where it went.
 */
class NodeSamplePanelTest {

    @Test void thePanelDoesNotRenderASecondPresentationOfItsOwn() {
        NodeSamplePanel sample = new NodeSamplePanel();

        assertNull(find(sample, workbench.EntityResultPanel.class),
                "a result table here would be a sampled instance rendered unlike a "
                        + "generated one");
        JLabel notice = labelContaining(sample, "Instances");
        assertNotNull(notice, "and the reader is told where the result appears instead");
    }

    /** Whoever owns the Instances view receives the result, exactly once. */
    @Test void theSampledResultIsHandedToTheInstancesViewer() {
        NodeSamplePanel sample = new NodeSamplePanel();
        List<ClassSampleResult> received = new ArrayList<>();
        sample.onClassSample(received::add);

        ClassSampleResult result = new ClassSampleResult(null, "Position", "population", 8, false);
        sample.acceptClassSample(result);
        flushEventQueue();

        assertEquals(List.of(result), received);
    }

    @Test void aMissingConsumerIsNotAFailure() {
        NodeSamplePanel sample = new NodeSamplePanel();
        sample.onClassSample(null);

        assertDoesNotThrow(() -> {
            sample.acceptClassSample(
                    new ClassSampleResult(null, "Position", "population", 8, false));
            flushEventQueue();
        });
    }

    /** acceptClassSample posts to the EDT, so the test waits for that to drain. */
    private static void flushEventQueue() {
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) {
                T found = find(container, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JLabel labelContaining(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null
                    && label.getText().contains(text)) {
                return label;
            }
            if (child instanceof Container container) {
                JLabel found = labelContaining(container, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
