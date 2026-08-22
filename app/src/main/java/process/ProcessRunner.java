package process;

import work.QueryContext;
import work.LogKind;
import work.LogListener;
import work.LogNode;
import work.WorkflowRecorder;
import work.CancellationToken;

/** Executes one root process and guarantees a terminal root log entry. */
public final class ProcessRunner {
    private final QueryContext queries;
    private final LogListener logs;
    private final ProcessInputHandler inputs;

    public ProcessRunner(QueryContext queries, LogListener logs, ProcessInputHandler inputs) {
        this.queries = queries;
        this.logs = logs;
        this.inputs = inputs;
    }

    public <R> ProcessOutcome<R> run(Process<R> process, CancellationToken cancellation) {
        ProcessPlan plan = process.plan();
        LogNode root = new LogNode(LogKind.WORKFLOW, plan.title());
        WorkflowRecorder recorder = new WorkflowRecorder(root);
        recorder.describeRoot(plan.description(), plan.parameters());
        recorder.setListener(logs);
        recorder.added();
        recorder.start();

        ProcessContext context = new ProcessContext(
                queries, recorder, root, cancellation, inputs);
        ProcessOutcome<R> outcome;
        try (CancellationToken.Registration ignored =
                     cancellation.onCancel(queries::cancelActiveWork)) {
            cancellation.throwIfCancelled();
            outcome = process.execute(context);
            if (outcome == null) {
                outcome = ProcessOutcome.failed(
                        new IllegalStateException("Process returned no outcome"));
            }
        } catch (Throwable error) {
            outcome = isCancellation(error)
                    ? ProcessOutcome.cancelled(null, "Cancelled")
                    : ProcessOutcome.failed(error);
        }
        recorder.finishProcess(ProcessContext.logStatus(outcome.status()),
                outcome.summary(), outcome.error());
        return outcome;
    }

    private static boolean isCancellation(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException
                    || t instanceof java.util.concurrent.CancellationException) {
                return true;
            }
        }
        return false;
    }
}
