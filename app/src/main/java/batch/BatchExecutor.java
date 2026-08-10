package batch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Runs a list of {@link WorkUnit}s to completion, narrowing any that prove too heavy.
 *
 * <p>The escalation, and why it is in this order:
 * <ol>
 *   <li>{@link BatchFailure#TRANSIENT} — retry the unit unchanged, up to
 *       {@code maxAttempts}. A truncated response is usually the endpoint closing a
 *       stream early under load; the identical request then succeeds in seconds.</li>
 *   <li>{@link BatchFailure#TOO_HEAVY}, or a transient failure that outlived its
 *       attempts — SPLIT and run the parts. Retrying an unfinishable request only
 *       spends the same wall-clock again.</li>
 *   <li>Nothing left to split, or {@code maxSplitDepth} reached — the run FAILS.</li>
 * </ol>
 *
 * <p>It fails rather than returning what it managed to collect. A half-filled result is
 * indistinguishable from real data once saved, and the point of batching is to finish the
 * work, not to make partial work look complete. What succeeded is not thrown away — it is
 * the caller's, and (Phase 2) the checkpoint's.
 *
 * <p>Cancellation is thread interruption, the same signal the extraction stages already
 * observe, so no cooperation from the caller's framework is required.
 */
public final class BatchExecutor {

    private final BatchPolicy policy;
    private final BatchProgress progress;

    public BatchExecutor(BatchPolicy policy, BatchProgress progress) {
        this.policy = policy == null ? BatchPolicy.defaults() : policy;
        this.progress = progress == null ? BatchProgress.NOOP : progress;
    }

    /** Runs every unit, or throws. Units execute in order; a split's parts run next,
     *  before the units that followed the one that split. */
    public void run(List<WorkUnit> units) throws Exception {
        if (units == null || units.isEmpty()) {
            return;
        }
        Deque<Attempt> pending = new ArrayDeque<>();
        for (int i = units.size() - 1; i >= 0; i--) {
            pending.push(new Attempt(units.get(i), 0));
        }

        while (!pending.isEmpty()) {
            throwIfCancelled();
            Attempt attempt = pending.pop();
            List<WorkUnit> parts = runOrSplit(attempt);
            if (parts != null) {
                for (int i = parts.size() - 1; i >= 0; i--) {
                    pending.push(new Attempt(parts.get(i), attempt.depth + 1));
                }
            }
        }
    }

    /**
     * Runs one unit, retrying transient failures.
     *
     * @return null when the unit completed; otherwise the parts it must be replaced by.
     */
    private List<WorkUnit> runOrSplit(Attempt attempt) throws Exception {
        Exception last = null;

        for (int tries = 1; tries <= policy.maxAttempts(); tries++) {
            throwIfCancelled();
            BatchProgress.Running running =
                    progress.started(attempt.unit.title(), attempt.unit.key());
            try {
                attempt.unit.run();
                running.done(tries == 1 ? "ok" : "ok (attempt " + tries + ")");
                return null;
            } catch (Exception e) {
                BatchFailure failure = BatchFailure.classify(e);
                running.failed(failure + ": " + message(e));
                if (failure == BatchFailure.CANCELLED) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                last = e;
                if (failure != BatchFailure.TRANSIENT) {
                    break;   // repeating it cannot help — escalate to a split now
                }
                if (tries < policy.maxAttempts()) {
                    pause(policy.retryBackoffMillis() * tries);
                }
            }
        }

        List<WorkUnit> parts = attempt.depth < policy.maxSplitDepth()
                ? attempt.unit.split()
                : List.of();
        if (parts == null || parts.isEmpty()) {
            throw new BatchFailedException(
                    "Batch \"" + attempt.unit.title() + "\" failed and cannot be "
                            + (attempt.depth < policy.maxSplitDepth()
                                    ? "split further"
                                    : "split again (depth " + attempt.depth + ")")
                            + ": " + message(last),
                    last);
        }
        progress.message("Splitting \"" + attempt.unit.title() + "\" into "
                                 + parts.size() + " (depth " + (attempt.depth + 1) + ")\n");
        return new ArrayList<>(parts);
    }

    private static void throwIfCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Batch execution was cancelled.");
        }
    }

    private static void pause(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private static String message(Throwable e) {
        if (e == null) {
            return "no error recorded";
        }
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private record Attempt(WorkUnit unit, int depth) { }

    /** A unit that could not be completed even after retries and splitting. */
    public static final class BatchFailedException extends Exception {
        public BatchFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
