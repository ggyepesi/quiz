package process.swing.workflow;

import process.Process;
import process.ProcessOutcome;

/**
 * Explicit workflow variant for reviewing and applying a result produced earlier.
 * It cannot accidentally be executed as a new process.
 */
public interface PreparedProcessWorkflowAction<R, D> extends ProcessWorkflowAction<R, D> {
    @Override ProcessOutcome<R> preparedOutcome();

    @Override default Process<R> process() {
        throw new IllegalStateException("A prepared review has no process to execute");
    }
}
