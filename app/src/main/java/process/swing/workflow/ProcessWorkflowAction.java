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
    /**
     * Whether result cards may be selected as a set. Single-card selection remains the
     * compatibility default; actions opt in only when applying independent decisions
     * together has domain-defined semantics.
     */
    default boolean multipleResultSelection() { return false; }
    /**
     * An already-computed result that should open directly for review and apply.
     * Null keeps the ordinary Plan → Execute → Results lifecycle.
     */
    default ProcessOutcome<R> preparedOutcome() { return null; }
    Process<R> process();
    ProcessWorkflowResults<D> results(ProcessOutcome<R> outcome);
    void apply(List<D> decisions) throws Exception;
}
