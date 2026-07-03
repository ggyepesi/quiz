package wikidata.explore.query.log;

/**
 * Handle to a single open log step, passed to a {@link LogStepBody}. The
 * body records request text and an optional success summary through it;
 * opening the step and completing it (ok / failed) is handled by the
 * scope runner, so the body cannot leave a step unbalanced.
 *
 * <p>When logging is disabled the recorder is null and every call is a
 * no-op, so query code can use the same body unconditionally.
 */
public final class LogStep {

    private final WorkflowRecorder recorder;
    private final LogNode node;
    private String summary;

    LogStep(WorkflowRecorder recorder, LogNode node) {
        this.recorder = recorder;
        this.node = node;
    }

    /** A no-op step used when logging is disabled (no recorder). */
    public static LogStep disabled() {
        return new LogStep(null, null);
    }

    public LogStep request(String text) {
        if (recorder != null) {
            recorder.append(node, text);
        }
        return this;
    }

    public LogStep summary(String summary) {
        this.summary = summary;
        return this;
    }

    /** Records a structured child sub-query under this step (collapsible in the
     *  log), instead of appending its text to this step's request blob. */
    public LogStep subquery(String title, String request, String summary) {
        if (recorder != null && node != null) {
            recorder.addSubquery(node, title, "SPARQL", request, summary);
        }
        return this;
    }

    /** Like {@link #subquery} but marks the child step FAILED, so a per-parent
     *  timeout (or other failure) is visible under this step instead of being
     *  lost when the query throws. */
    public LogStep subqueryFailed(String title, String request, String error) {
        if (recorder != null && node != null) {
            recorder.addSubquery(node, title, "SPARQL", request,
                    error == null || error.isBlank() ? "FAILED" : "FAILED: " + error,
                    LogStatus.FAILED);
        }
        return this;
    }

    /** Opens a child sub-query in the RUNNING state (visible while it executes);
     *  finish it with {@link #completeSubquery} / {@link #failSubquery}. Returns
     *  null when logging is disabled, which the finish calls tolerate. */
    public LogNode beginSubquery(String title, String request) {
        return recorder != null && node != null
                ? recorder.beginSubquery(node, title, "SPARQL", request)
                : null;
    }

    public void completeSubquery(LogNode child, String summary) {
        if (recorder != null) {
            recorder.completeSubquery(child, summary, LogStatus.OK);
        }
    }

    public void failSubquery(LogNode child, String error) {
        if (recorder != null) {
            recorder.completeSubquery(child,
                    error == null || error.isBlank() ? "FAILED" : "FAILED: " + error,
                    LogStatus.FAILED);
        }
    }

    /** Opens a child GROUP node (RUNNING) and returns a step bound to it, so
     *  sub-queries logged on the returned step nest under the group. Finalize with
     *  {@link #completeGroup}. Returns a disabled step when logging is off. */
    public LogStep beginGroup(String title) {
        LogNode g = recorder != null && node != null
                ? recorder.beginSubquery(node, title, "", null)
                : null;
        return g == null ? disabled() : new LogStep(recorder, g);
    }

    /** Completes THIS step's own node (used to close a group step). */
    public void completeGroup(String summary) {
        if (recorder != null && node != null) {
            recorder.completeSubquery(node, summary, LogStatus.OK);
        }
    }

    public LogNode node() {
        return node;
    }

    String summaryText() {
        return summary;
    }
}
