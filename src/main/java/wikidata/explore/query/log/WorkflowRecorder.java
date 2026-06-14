package wikidata.explore.query.log;

import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryStatus;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Drives one workflow's log tree: owns the root {@link LogNode}, the stack
 * of currently-open steps, and the {@link LogListener}. This is the only
 * place {@link LogNode}s are mutated, so all writes happen on the worker
 * thread that runs the workflow; the listener marshals to the EDT.
 *
 * <p>The stack is touched only here, on that single thread, so it needs no
 * synchronization.
 */
public class WorkflowRecorder {

    private final LogNode root;

    private final Deque<LogNode> stack =
            new ArrayDeque<>();

    private LogListener listener;

    public WorkflowRecorder(LogNode root) {
        this.root = root == null
                ? new LogNode(LogKind.WORKFLOW, "Workflow")
                : root;
    }

    public static WorkflowRecorder forQuery(Query<?> query) {
        LogNode root =
                new LogNode(
                        LogKind.WORKFLOW,
                        query == null ? null : query.purpose());

        if (query != null) {
            root.description(query.description());
            root.parameters(LogNode.formatParameters(query.parameters()));
        }

        return new WorkflowRecorder(root);
    }

    public LogNode root() {
        return root;
    }

    public void setListener(LogListener listener) {
        this.listener = listener;
    }

    public void added() {
        fire(true);
    }

    public void start() {
        root.start();
        fire(false);
    }

    /** Opens a query step nested under the current step (or the root). */
    public LogNode beginQuery(
            String title,
            String queryType,
            String skeleton,
            Map<String, String> parameters) {

        LogNode node =
                new LogNode(LogKind.QUERY, title)
                        .queryType(queryType)
                        .skeleton(skeleton)
                        .parameters(LogNode.formatParameters(parameters));

        LogNode parent = stack.peek();
        (parent == null ? root : parent).addStep(node);

        node.start();
        stack.push(node);
        fire(false);
        return node;
    }

    /**
     * Runs {@code body} inside a freshly-opened step, completing it as ok
     * on normal return or failed / cancelled on exception. The step can
     * never be left unbalanced.
     */
    public <T> T step(
            String title,
            String queryType,
            String skeleton,
            Map<String, String> parameters,
            LogStepBody<T> body) throws Exception {

        LogNode node =
                beginQuery(title, queryType, skeleton, parameters);

        LogStep step = new LogStep(this, node);

        try {
            T result = body.run(step);
            complete(node, LogStatus.OK, step.summaryText(), null);
            return result;
        } catch (Exception e) {
            complete(node, statusFor(e), null, e);
            throw e;
        }
    }

    public void append(LogNode node, String text) {
        if (node == null || text == null || text.isBlank()) {
            return;
        }

        node.appendRequest(text);
        fire(false);
    }

    /** Records a standalone message on the current step, or the root. */
    public void message(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        LogNode target = stack.peek();
        (target == null ? root : target).appendRequest(text);
        fire(false);
    }

    public void complete(
            LogNode node,
            LogStatus status,
            String summary,
            Throwable error) {

        if (node == null) {
            return;
        }

        node.complete(
                status,
                summary,
                error == null ? null : error.getMessage());

        stack.remove(node);
        fire(false);
    }

    /**
     * Closes the workflow, draining any steps the body left open. The
     * {@code summary} is the workflow-level, domain-specific result text
     * (e.g. "92 objects") supplied by the query; the recorder no longer
     * assumes "rows".
     */
    public void finish(QueryStatus status, String summary, String error) {
        LogStatus terminal = LogStatus.from(status);

        while (!stack.isEmpty()) {
            LogNode open = stack.pop();
            open.complete(
                    terminal == LogStatus.OK
                            ? LogStatus.OK
                            : terminal,
                    terminal == LogStatus.OK
                            ? "Finished with workflow"
                            : null,
                    error);
        }

        root.complete(
                terminal,
                terminal == LogStatus.OK ? summary : null,
                error);
        fire(false);
    }

    private void fire(boolean added) {
        if (listener != null) {
            listener.logChanged(root, added);
        }
    }

    private static LogStatus statusFor(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException
                    || t instanceof CancellationException) {
                return LogStatus.CANCELLED;
            }
        }
        return LogStatus.FAILED;
    }
}
