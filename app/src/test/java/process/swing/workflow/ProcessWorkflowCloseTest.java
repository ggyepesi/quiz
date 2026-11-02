package process.swing.workflow;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A workflow window is the only way back to a running process; closing it cannot silently
 * leave that process running without an observer. */
class ProcessWorkflowCloseTest {

    @Test void aRunningWorkflowStaysOpenWhenCancellationIsDeclined() {
        assertFalse(SwingProcessWorkflow.closeAllowed(
                ProcessWorkflowState.Stage.RUNNING, () -> false));
    }

    @Test void aRunningWorkflowClosesAfterCancellationIsConfirmed() {
        assertTrue(SwingProcessWorkflow.closeAllowed(
                ProcessWorkflowState.Stage.RUNNING, () -> true));
    }

    @Test void aPlanOrResultClosesWithoutAskingAboutCancellation() {
        AtomicInteger requests = new AtomicInteger();

        assertTrue(SwingProcessWorkflow.closeAllowed(
                ProcessWorkflowState.Stage.PLAN, () -> {
                    requests.incrementAndGet();
                    return false;
                }));
        assertTrue(SwingProcessWorkflow.closeAllowed(
                ProcessWorkflowState.Stage.RESULTS, () -> {
                    requests.incrementAndGet();
                    return false;
                }));
        assertEquals(0, requests.get());
    }
}
