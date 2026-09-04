package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.query.result.ClassSampleResult;

import javax.swing.JButton;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Sample panel is about what is selected — one button, one result, one reason.
 *
 * <p>Three defects, all of the same shape: something on this panel was left describing a
 * selection that had moved on. Rows sampled from one class stayed on screen under
 * another class's editor; two buttons each decided their own enablement, so an aggregate
 * offered both and honoured neither; and the runner re-enabled them whenever anything
 * anywhere in the workbench finished running.
 */
class SampleFollowsTheSelectionTest {

    /** One question — sample what is selected — so one control asks it. */
    @Test void thereIsOneSampleButton() {
        List<JButton> buttons = buttonsIn(new NodeSamplePanel());

        assertEquals(1, buttons.size(),
                "two sample buttons make the reader work out which one their selection "
                        + "matched: " + buttons.stream().map(JButton::getText).toList());
    }

    /** A result belongs to the subject it was taken from, and no further. */
    @Test void aResultDoesNotSurviveTheSelectionThatProducedIt() {
        NodeSamplePanel sample = new NodeSamplePanel();
        List<ClassSampleResult> received = new ArrayList<>();
        sample.onClassSample(received::add);

        sample.showSubject("Nomination");
        sample.acceptClassSample(
                new ClassSampleResult(null, "Nomination", "population", 8, false));
        flushEventQueue();
        assertTrue(statusOf(sample).contains("sampled instance"),
                "the sample it just took: " + statusOf(sample));

        sample.showSubject("Person");
        assertFalse(statusOf(sample).contains("sampled instance"),
                "Nomination's rows read as Person's under Person's editor: "
                        + statusOf(sample));
    }

    /** Re-selecting the same thing is not a change, so it keeps what is shown. */
    @Test void reSelectingTheSameSubjectKeepsTheResult() {
        NodeSamplePanel sample = new NodeSamplePanel();

        sample.showSubject("Nomination");
        sample.acceptClassSample(
                new ClassSampleResult(null, "Nomination", "population", 8, false));
        flushEventQueue();

        sample.showSubject("Nomination");

        assertTrue(statusOf(sample).contains("sampled instance"),
                "applying a field re-selects; that must not wipe the sample: "
                        + statusOf(sample));
    }

    /**
     * With nothing sampleable, the button says so where the reader is looking.
     *
     * <p>An aggregate class cannot be sampled yet. That reason lived only in a disabled
     * button's tooltip — while the OTHER button sat enabled beside it, offering a sample
     * it would refuse the moment it was pressed.
     */
    @Test void anUnsampleableSelectionDisablesTheButtonAndSaysWhy() {
        NodeSamplePanel sample = new NodeSamplePanel();
        sample.setClassSampleSupplier(() -> null);
        sample.setClassSampleUnavailableReason(
                () -> "Aggregate class sampling is not implemented yet.");
        sample.setQueryRunner(runner());

        sample.showSubject("NobelPrize");

        JButton button = buttonsIn(sample).getFirst();
        assertFalse(button.isEnabled(), "nothing here can be sampled");
        assertTrue(statusOf(sample).contains("not implemented yet"),
                "the reason has to be visible, not hidden in a tooltip: "
                        + statusOf(sample));
    }

    /** A selected field is the more specific selection, so it is what gets sampled. */
    @Test void aSelectedFieldIsWhatTheButtonOffers() {
        NodeSamplePanel sample = new NodeSamplePanel();
        sample.setClassSampleSupplier(() -> null);
        sample.setClassSampleUnavailableReason(() -> "no class sample");
        sample.setFieldSampleSupplier(
                () -> new wikidata.explore.model.FieldSampleContext(null, null, null));
        sample.setQueryRunner(runner());

        sample.showSubject("Nomination.nominee");

        JButton button = buttonsIn(sample).getFirst();
        assertTrue(button.isEnabled(),
                "a field is sampleable even when its class is not");
        assertTrue(button.getText().contains("field"),
                "the button says which sample it will take: " + button.getText());
    }

    /**
     * Sample is one of the buttons a run disables, and Cancel is what replaces it.
     *
     * <p>Availability and the run state are two conditions with two owners, and the fix
     * for one broke the other: re-asking availability when the run state changes is what
     * stops a finishing run from enabling an unsampleable selection, and it also
     * re-enabled Sample in the middle of its own run unless availability honours the run
     * state too.
     */
    @Test void samplingIsNotOfferedWhileSomethingIsRunning() throws Exception {
        NodeSamplePanel sample = new NodeSamplePanel();
        sample.setClassSampleSupplier(() -> new StubQuery());
        sample.setClassSampleUnavailableReason(() -> "");
        wikidata.explore.query.swing.SwingQueryRunner runner = runner();
        sample.setQueryRunner(runner);
        sample.showSubject("Nomination");
        assertTrue(buttonsIn(sample).getFirst().isEnabled(), "sampleable to begin with");

        setRunning(runner, true);

        assertFalse(buttonsIn(sample).getFirst().isEnabled(),
                "asking again while the answer is being fetched is the same sample twice");
        assertTrue(statusOf(sample).contains("already running"), statusOf(sample));

        setRunning(runner, false);

        assertTrue(buttonsIn(sample).getFirst().isEnabled(),
                "and it comes back when the run ends");
    }

    /** A query that only has to exist — nothing here runs it. */
    private record StubQuery() implements work.Query<
            wikidata.explore.query.result.ClassSampleResult> {
        @Override public String purpose() { return "stub"; }
        @Override public String skeleton() { return "stub"; }
        @Override public java.util.Map<String, String> parameters() {
            return java.util.Map.of();
        }
        @Override public wikidata.explore.query.result.ClassSampleResult execute(
                work.QueryContext context) { return null; }
        @Override public int rowCount(
                wikidata.explore.query.result.ClassSampleResult result) { return 0; }
    }

    private static void setRunning(
            wikidata.explore.query.swing.SwingQueryRunner runner, boolean running)
            throws Exception {
        java.lang.reflect.Method method =
                runner.getClass().getDeclaredMethod("setRunning", boolean.class);
        method.setAccessible(true);
        method.invoke(runner, running);
        flushEventQueue();
    }

    /**
     * The runner answers "is something running", not "can this be sampled".
     *
     * <p>It enables every registered run button when a run ends. That is why a field
     * sample elsewhere in the workbench re-enabled a Sample button whose selection could
     * not be sampled at all.
     */
    @Test void aRunEndingDoesNotReEnableAnUnsampleableSelection() throws Exception {
        NodeSamplePanel sample = new NodeSamplePanel();
        sample.setClassSampleSupplier(() -> null);
        sample.setClassSampleUnavailableReason(() -> "nothing to sample here");
        wikidata.explore.query.swing.SwingQueryRunner runner = runner();
        sample.setQueryRunner(runner);
        sample.showSubject("NobelPrize");

        setRunning(runner, true);
        setRunning(runner, false);

        assertFalse(buttonsIn(sample).getFirst().isEnabled(),
                "the runner had the last word on a question it was not asked");
    }

    private static wikidata.explore.query.swing.SwingQueryRunner runner() {
        return new wikidata.explore.query.swing.SwingQueryRunner(null, null);
    }

    private static String statusOf(Container root) {
        StringBuilder text = new StringBuilder();
        for (Component child : root.getComponents()) {
            if (child instanceof javax.swing.JLabel label && label.getText() != null) {
                text.append(label.getText()).append('\n');
            }
            if (child instanceof Container container) text.append(statusOf(container));
        }
        return text.toString();
    }

    private static List<JButton> buttonsIn(Container root) {
        List<JButton> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button) found.add(button);
            if (child instanceof Container container) found.addAll(buttonsIn(container));
        }
        return found;
    }

    private static void flushEventQueue() {
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
