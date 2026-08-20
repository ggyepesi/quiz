package process.swing.workflow;

import process.ProcessWorkflowPipeline;

import process.Process;
import process.ProcessOutcome;

import java.util.List;
import javax.swing.JComponent;

/**
 * Plug-in contract for a TransformApp curation operation.
 *
 * <p>An action describes domain work only. The shared Swing host owns dialogs, lifecycle,
 * logging, cancellation, virtualized rendering and error handling.</p>
 */
public interface ProcessWorkflowAction<R, D> {
    String id();
    ProcessWorkflowPlan plan();
    /** Optional executable pipeline shown consistently in Plan, Running and Results. */
    default ProcessWorkflowPipeline pipeline() { return null; }
    /** Optional run-scoped controls, hosted consistently on the Plan page. */
    default JComponent executionSettings() { return null; }
    /** Whether this terminal result may be applied. */
    default boolean applyAllowed(process.ProcessStatus status) { return true; }
    Process<R> process();
    ProcessWorkflowResults<D> results(ProcessOutcome<R> outcome);
    void apply(List<D> decisions) throws Exception;
}
