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

    public LogNode node() {
        return node;
    }

    String summaryText() {
        return summary;
    }
}
