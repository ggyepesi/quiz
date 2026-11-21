package process.swing.workflow;

import org.junit.jupiter.api.Test;
import process.ProcessWorkflowPipeline;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a phase's run state READS — the panel's own concern, not the pipeline's. */
class ProcessWorkflowPipelinePanelTest {

    @Test void theExplanationFollowsThePhaseThatStartsRunning() throws Exception {
        ProcessWorkflowPipeline pipeline = new ProcessWorkflowPipeline(List.of(
                new ProcessWorkflowPipeline.Phase(
                        "first", "Generate project", "", List.of()),
                new ProcessWorkflowPipeline.Phase(
                        "second", "Generate project", "", List.of())));
        AtomicReference<ProcessWorkflowPipelinePanel> shown = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> shown.set(
                new ProcessWorkflowPipelinePanel(pipeline)));

        pipeline.start("first", "running first generation");
        SwingUtilities.invokeAndWait(() -> { });
        pipeline.complete("first", "first generation complete");
        pipeline.start("second", "running second generation");
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals("second", shown.get().selectedPhaseId());
    }

    @Test void formatsElapsedTimeCompactly() {
        assertEquals("<1s", ProcessWorkflowPipelinePanel.formatElapsed(999));
        assertEquals("12s", ProcessWorkflowPipelinePanel.formatElapsed(12_345));
        assertEquals("2m 05s", ProcessWorkflowPipelinePanel.formatElapsed(125_000));
        assertEquals("1h 02m 03s", ProcessWorkflowPipelinePanel.formatElapsed(3_723_000));
    }

    @Test void phaseBlocksHaveRoomForTitleStatusAndRunSummary() throws Exception {
        ProcessWorkflowPipeline pipeline = new ProcessWorkflowPipeline(List.of(
                new ProcessWorkflowPipeline.Phase(
                        "category-members", "Discover Wikipedia category members",
                        "", List.of())));
        AtomicReference<ProcessWorkflowPipelinePanel> shown = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> shown.set(
                new ProcessWorkflowPipelinePanel(pipeline)));

        pipeline.start("category-members",
                "Reading category members and resolving their configured instances");
        SwingUtilities.invokeAndWait(() -> { });

        JButton phase = findButton(shown.get());
        assertTrue(phase.getPreferredSize().height >= 100,
                "A phase with a wrapping title and summary must not be clipped");
        assertTrue(phase.getText().contains("Reading category members"));
    }

    private static JButton findButton(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button) return button;
            if (component instanceof Container container) {
                JButton nested = findButtonOrNull(container);
                if (nested != null) return nested;
            }
        }
        throw new AssertionError("No phase button found");
    }

    private static JButton findButtonOrNull(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button) return button;
            if (component instanceof Container container) {
                JButton nested = findButtonOrNull(container);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
