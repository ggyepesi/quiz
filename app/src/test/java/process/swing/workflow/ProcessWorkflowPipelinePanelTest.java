package process.swing.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a phase's run state READS — the panel's own concern, not the pipeline's. */
class ProcessWorkflowPipelinePanelTest {

    @Test void formatsElapsedTimeCompactly() {
        assertEquals("<1s", ProcessWorkflowPipelinePanel.formatElapsed(999));
        assertEquals("12s", ProcessWorkflowPipelinePanel.formatElapsed(12_345));
        assertEquals("2m 05s", ProcessWorkflowPipelinePanel.formatElapsed(125_000));
        assertEquals("1h 02m 03s", ProcessWorkflowPipelinePanel.formatElapsed(3_723_000));
    }
}
