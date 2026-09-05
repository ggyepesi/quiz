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
 * A class sample is presented HERE, through the shared Viewable renderer.
 *
 * <p>Two ways this has been wrong. It rendered its own QID/label table, which made a
 * sampled instance a second presentation KIND — the same object shown one way when
 * generated and another when sampled, with none of the nested-field expansion, links or
 * view configuration the ordinary result has. Then it rendered through the shared
 * renderer AND a window rendered the same result too, which was a second COPY: the same
 * objects twice, in two sizes, disagreeing about how they looked.
 *
 * <p>One renderer, one place. The sample is read beside the class's own editor and its
 * explanation, so that is where it is drawn.
 */
class NodeSamplePanelTest {

    /**
     * The query text lives in the query log, not in a tab of its own.
     *
     * <p>There was a "SPARQL" tab beside the results, and nothing ever wrote to it — it
     * was cleared in three places and filled in none. Nor should it have been: a
     * sample's steps already carry their request on the log node, which renders as a
     * link that opens the Wikidata Query Service on that exact query. A text area here
     * would have been a worse second copy of something that exists.
     */
    @Test void thereIsNoSecondPlaceForTheQueryText() {
        NodeSamplePanel sample = new NodeSamplePanel();

        assertNull(find(sample, javax.swing.JTabbedPane.class),
                "one thing to show, so nothing to choose between");
    }

    /** One renderer — the shared one — and no second table beside it. */
    @Test void aSampledInstanceIsRenderedTheWayAGeneratedOneIs() {
        NodeSamplePanel sample = new NodeSamplePanel();

        assertNull(find(sample, workbench.EntityResultPanel.class),
                "a bespoke result table here would be a second presentation kind");
        assertNotNull(
                find(sample, wikidata.explore.query.swing.QueryObjectResultPanel.class),
                "the shared Viewable renderer, the one every view of generated objects "
                        + "uses");
    }

    /**
     * And nowhere else: the frame must not open a window on the same result.
     *
     * <p>That is what made the instances appear twice. The consumer stays, because what
     * reads a sample without rendering it still needs it — the identity preview compares
     * a key change against real instances — so this asserts the shape, not that nobody
     * listens.
     */
    @Test void theFrameDoesNotOpenASecondViewOfTheSameResult() {
        long windows = java.util.Arrays.stream(
                        ModelBuilderFrame.class.getDeclaredFields())
                .filter(field -> javax.swing.JFrame.class.isAssignableFrom(field.getType()))
                .filter(field -> field.getName().toLowerCase().contains("sample"))
                .count();

        assertEquals(0, windows,
                "a sample window is a second copy of what the Sample tab already draws");
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
