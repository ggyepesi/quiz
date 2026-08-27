package process.swing.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessWorkflowStateTest {
    @Test void planCanExplicitlyRepresentNoWork() {
        ProcessWorkflowPlan plan = new ProcessWorkflowPlan(
                "Identity plan", "Inspect existing identities", java.util.List.of(),
                false, "All instances are already identified");

        assertEquals(false, plan.executable());
        assertEquals("All instances are already identified", plan.noWorkMessage());
    }

    @Test void followsTheExecutableLifecycle() {
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

    @Test void preparedStateCanGoDirectlyToReviewAndApply() {
        ProcessWorkflowState state = new ProcessWorkflowState();
        state.review();
        assertEquals(ProcessWorkflowState.Stage.RESULTS, state.stage());
        state.apply(); state.applied();
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
