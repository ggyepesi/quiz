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

    /**
     * A sampled instance is rendered in ONE place, the way a generated one is.
     *
     * <p>Two ways to fail this, and both have happened. A bespoke table here is a second
     * presentation KIND — the same object shown one way when generated and another when
     * sampled. The shared renderer here is a second COPY: the window renders the result
     * too, and two views of one result that disagree about how it looks leave the reader
     * deciding which to believe. Neither belongs in this panel.
     */
    @Test void aSampledInstanceIsRenderedInOnePlace() {
        NodeSamplePanel sample = new NodeSamplePanel();

        assertNull(find(sample, workbench.EntityResultPanel.class),
                "a bespoke result table here would be a second presentation");
        assertNull(
                find(sample, wikidata.explore.query.swing.QueryObjectResultPanel.class),
                "and the shared renderer here would be a second copy of the one result");
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

    /**
     * Sampling compiles the model first, so its usual failure IS the validation report —
     * several lines naming the class and what it lacks. "Class sample failed." reduced
     * that to three words and left the rest in a log nobody was looking at, which is how
     * sampling OfficeHolding managed to say nothing about the subject it has not
     * declared.
     */
    @Test void aFailureShowsWhatTheModelSaidRatherThanThatItFailed() throws Exception {
        NodeSamplePanel sample = new NodeSamplePanel();
        String report = "Cannot compile invalid model:\n"
                + "ERROR: OfficeHolding: A Statement class must explicitly expose its "
                + "subject as a single ENTITY field, a subject-fallback field, or a "
                + "participants list.";

        java.lang.reflect.Method show = NodeSamplePanel.class.getDeclaredMethod(
                "showFailure", String.class, Throwable.class);
        show.setAccessible(true);
        show.invoke(sample, "Class sample failed", new IllegalStateException(report));

        String shown = allText(sample);
        assertTrue(shown.contains("must explicitly expose its subject"),
                "the reason has to be readable where the reader is looking: " + shown);
    }

    private static String allText(java.awt.Container root) {
        StringBuilder text = new StringBuilder();
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null) {
                text.append(label.getText()).append('\n');
            }
            if (child instanceof javax.swing.JTextArea area && area.getText() != null) {
                text.append(area.getText()).append('\n');
            }
            if (child instanceof java.awt.Container container) {
                text.append(allText(container));
            }
        }
        return text.toString();
    }
}
