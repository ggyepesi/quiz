package process.swing.workflow;

import org.junit.jupiter.api.Test;
import process.ProcessWorkflowPipeline;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
