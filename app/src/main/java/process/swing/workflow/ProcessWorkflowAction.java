package process.swing.workflow;

import process.Process;
import process.ProcessOutcome;

import java.util.List;

/**
 * Plug-in contract for a TransformApp curation operation.
 *
 * <p>An action describes domain work only. The shared Swing host owns dialogs, lifecycle,
 * logging, cancellation, virtualized rendering and error handling.</p>
 */
public interface ProcessWorkflowAction<R, D> {
    String id();
    ProcessWorkflowPlan plan();
    Process<R> process();
    ProcessWorkflowResults<D> results(ProcessOutcome<R> outcome);
    void apply(List<D> decisions) throws Exception;
}
