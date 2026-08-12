package process;

import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.log.LogNode;
import wikidata.explore.query.log.LogStatus;
import wikidata.explore.query.log.WorkflowRecorder;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Run-scoped services. Child contexts own independent cancellation and log nodes. */
public final class ProcessContext {
    private final QueryContext queries;
    private final WorkflowRecorder recorder;
    private final LogNode logNode;
    private final CancellationToken cancellation;
    private final ProcessInputHandler inputs;

    public ProcessContext(
            QueryContext queries,
            WorkflowRecorder recorder,
            LogNode logNode,
            CancellationToken cancellation,
            ProcessInputHandler inputs) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.recorder = recorder;
        this.logNode = logNode;
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.inputs = inputs == null ? ProcessInputHandler.unsupported() : inputs;
    }

    public QueryContext queries() {
        QueryContext bound = recorder == null ? queries : queries.withRecorder(recorder, logNode);
        return bound.withCancellation(cancellation);
    }

    public CancellationToken cancellation() {
        return cancellation;
    }

    public void message(String text) {
        if (recorder != null) recorder.message(logNode, text);
    }

    public <T> T input(ProcessInputRequest<T> request) throws Exception {
        cancellation.throwIfCancelled();
        T answer = inputs.request(request, cancellation);
        cancellation.throwIfCancelled();
        return answer;
    }

    public <T> ProcessOutcome<T> run(Process<T> subprocess) {
        return run(subprocess, ignored -> { });
    }

    /**
     * Runs a child and exposes only that child's token to orchestration/UI code.
     * Cancelling it does not cancel its siblings or parent.
     */
    public <T> ProcessOutcome<T> run(
            Process<T> subprocess,
            java.util.function.Consumer<CancellationToken> cancellationOwner) {
        Objects.requireNonNull(subprocess, "subprocess");
        ProcessPlan plan = subprocess.plan();
        CancellationToken childCancellation = cancellation.child();
        if (cancellationOwner != null) cancellationOwner.accept(childCancellation);
        LogNode child = recorder == null ? null
                : recorder.beginProcess(logNode, plan.title(), plan.description(), plan.parameters());
        ProcessContext childContext = new ProcessContext(
                queries, recorder, child, childCancellation, inputs);
        ProcessOutcome<T> outcome;
        try {
            childCancellation.throwIfCancelled();
            outcome = Objects.requireNonNull(
                    subprocess.execute(childContext), "Process returned no outcome");
        } catch (Throwable error) {
            outcome = isCancellation(error)
                    ? ProcessOutcome.cancelled(null, "Cancelled")
                    : ProcessOutcome.failed(error);
        } finally {
            // Completion is below so the exact outcome is reflected in the log.
        }
        if (recorder != null) {
            recorder.completeProcess(child, logStatus(outcome.status()),
                    outcome.summary(), outcome.error());
        }
        return outcome;
    }

    private static boolean isCancellation(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    static LogStatus logStatus(ProcessStatus status) {
        return switch (status) {
            case SUCCEEDED -> LogStatus.OK;
            case PARTIAL -> LogStatus.PARTIAL;
            case FAILED -> LogStatus.FAILED;
            case CANCELLED -> LogStatus.CANCELLED;
            case PENDING, RUNNING -> throw new IllegalArgumentException("Non-terminal status");
        };
    }
}
