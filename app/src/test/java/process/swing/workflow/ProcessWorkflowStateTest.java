package process.swing.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessWorkflowStateTest {
    @Test void followsTheOnlySupportedLifecycle() {
        ProcessWorkflowState state = new ProcessWorkflowState();
        assertEquals(ProcessWorkflowState.Stage.PLAN, state.stage());
        state.execute();
        assertEquals(ProcessWorkflowState.Stage.RUNNING, state.stage());
        state.results();
        assertEquals(ProcessWorkflowState.Stage.RESULTS, state.stage());
        state.apply();
        assertEquals(ProcessWorkflowState.Stage.APPLYING, state.stage());
        state.applied();
        assertEquals(ProcessWorkflowState.Stage.COMPLETE, state.stage());
    }

    @Test void rejectsSkippingAWorkflowPhase() {
        ProcessWorkflowState state = new ProcessWorkflowState();
        assertThrows(IllegalStateException.class, state::results);
        assertThrows(IllegalStateException.class, state::apply);
    }

    @Test void failedApplyReturnsToResultsForRetry() {
        ProcessWorkflowState state = new ProcessWorkflowState();
        state.execute(); state.results(); state.apply(); state.retryApply();
        assertEquals(ProcessWorkflowState.Stage.RESULTS, state.stage());
    }
}
