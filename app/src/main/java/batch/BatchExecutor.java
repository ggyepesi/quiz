package batch;

import process.CancellationToken;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Runs durable work partitions to completion, narrowing only failures for which a smaller
 * request can help. Execution and publication are deliberately separate: a failed attempt
 * cannot leave a partially mutated registry or result collection behind.
 *
 * <p>The checkpoint journal records the initial roots once, then appends one SPLIT or
 * COMPLETE event per state transition. This preserves adaptive splits exactly without
 * rewriting the growing frontier. A successful result is committed before COMPLETE is
 * appended, so {@link ResultCommitter} must be idempotent for a descriptor key to cover a
 * stop in that small commit/checkpoint window.
 */
public final class BatchExecutor<R> {
    private final BatchPolicy policy;
    private final BatchProgress progress;
    private final FailureClassifier failureClassifier;
    private final CancellationToken cancellation;
    private final BatchCheckpointStore checkpoints;

    public BatchExecutor(BatchPolicy policy, BatchProgress progress) {
        this(policy, progress, FailureClassifier.standard(),
                new CancellationToken(), BatchCheckpointStore.NONE);
    }

    public BatchExecutor(
            BatchPolicy policy,
            BatchProgress progress,
            FailureClassifier failureClassifier,
            CancellationToken cancellation,
            BatchCheckpointStore checkpoints) {
        this.policy = policy == null ? BatchPolicy.defaults() : policy;
        this.progress = progress == null ? BatchProgress.NOOP : progress;
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.checkpoints = checkpoints == null ? BatchCheckpointStore.NONE : checkpoints;
    }

    /** Runs without persistence and discards successfully computed result values. */
    public void run(List<? extends WorkUnit<R>> units) throws Exception {
        run(units, ResultCommitter.noOp());
    }

    /** Runs without persistence, publishing only complete unit results. */
    public void run(
            List<? extends WorkUnit<R>> units,
            ResultCommitter<R> committer) throws Exception {
        if (checkpoints != BatchCheckpointStore.NONE) {
            throw new IllegalStateException(
                    "A runKey and WorkUnitFactory are required when checkpointing is enabled");
        }
        execute("uncheckpointed", units, null, committer, false);
    }

    /**
     * Starts or resumes a named run. When a checkpoint exists and resume is enabled, the
     * persisted leaf queue is authoritative; {@code initialUnits} are not replayed.
     */
    public void run(
            String runKey,
            List<? extends WorkUnit<R>> initialUnits,
            WorkUnitFactory<R> restorer,
            ResultCommitter<R> committer) throws Exception {
        if (runKey == null || runKey.isBlank()) {
            throw new IllegalArgumentException("runKey must not be blank");
        }
        execute(runKey, initialUnits, Objects.requireNonNull(restorer, "restorer"),
                committer, true);
    }

    private void execute(
            String runKey,
            List<? extends WorkUnit<R>> initialUnits,
            WorkUnitFactory<R> restorer,
            ResultCommitter<R> committer,
            boolean persistent) throws Exception {
        Objects.requireNonNull(committer, "committer");
        cancellation.throwIfCancelled();

        Deque<Attempt<R>> pending = new ArrayDeque<>();
        Set<String> knownKeys = new LinkedHashSet<>();
        Optional<BatchCheckpoint> saved = persistent && policy.resume()
                ? checkpoints.recover(runKey)
                : Optional.empty();

        if (saved.isPresent()) {
            restore(saved.orElseThrow(), restorer, pending, knownKeys);
            progress.message("Resuming \"" + runKey + "\" with " + pending.size()
                    + " pending unit(s)\n");
        } else {
            initialise(initialUnits, pending);
            pending.forEach(attempt -> knownKeys.add(attempt.unit().key()));
            if (persistent) {
                checkpoints.start(runKey, pendingSnapshot(pending));
            }
        }

        while (!pending.isEmpty()) {
            cancellation.throwIfCancelled();
            Attempt<R> attempt = pending.peekFirst();
            RunResult<R> result = runOrSplit(attempt, committer);

            if (result.completed()) {
                if (persistent) {
                    try {
                        checkpoints.complete(runKey, attempt.unit().key());
                    } catch (Exception checkpointError) {
                        result.running().failed(
                                "CHECKPOINT: " + message(checkpointError));
                        throw checkpointError;
                    }
                }
                pending.removeFirst();
                result.running().done(result.summary());
            } else {
                List<WorkUnit<R>> parts = validateSplit(
                        attempt, result.parts(), knownKeys);
                if (persistent) {
                    checkpoints.split(
                            runKey,
                            attempt.unit().key(),
                            parts.stream()
                                    .map(part -> new BatchCheckpoint.PendingWork(
                                            part.descriptor(), attempt.depth() + 1))
                                    .toList());
                }
                pending.removeFirst();
                for (int i = parts.size() - 1; i >= 0; i--) {
                    pending.addFirst(new Attempt<>(parts.get(i), attempt.depth() + 1));
                }
                parts.forEach(part -> knownKeys.add(part.key()));
                progress.message("Splitting \"" + attempt.unit().title() + "\" into "
                        + parts.size() + " (depth " + (attempt.depth() + 1) + ")\n");
            }
        }

        if (persistent) {
            checkpoints.finish(runKey);
        }
    }

    private RunResult<R> runOrSplit(
            Attempt<R> attempt,
            ResultCommitter<R> committer) throws Exception {
        Exception last = null;

        // The loop bound is the largest budget any kind could claim; each failure then
        // checks its OWN budget, because UNAVAILABLE deliberately gets fewer attempts
        // than TRANSIENT (see BatchPolicy: retries here stack on the transport's).
        int maxTries = Math.max(policy.maxAttempts(), policy.maxUnavailableAttempts());
        for (int tries = 1; tries <= maxTries; tries++) {
            cancellation.throwIfCancelled();
            BatchProgress.Running running = Objects.requireNonNull(
                    progress.started(attempt.unit().title(), attempt.unit().key()),
                    "BatchProgress.started returned null");
            R value;
            try {
                value = attempt.unit().execute();
            } catch (Exception error) {
                FailureDecision decision = Objects.requireNonNull(
                        failureClassifier.classify(error),
                        "FailureClassifier returned null");
                BatchFailure failure = decision.failure();
                running.failed(failure + ": " + message(error));
                if (failure == BatchFailure.CANCELLED) {
                    restoreInterruptIfNeeded(error);
                    throw error;
                }
                if (failure == BatchFailure.FATAL) {
                    throw failed(attempt, "encountered a fatal error", error);
                }
                last = error;
                boolean retryable = failure == BatchFailure.TRANSIENT
                        || failure == BatchFailure.UNAVAILABLE;
                if (retryable && tries < policy.attemptsFor(failure)) {
                    long policyDelay = multiplyWithoutOverflow(
                            policy.retryBackoffMillis(), tries);
                    pause(Math.max(policyDelay, decision.retryAfterMillis()));
                    continue;
                }
                if (failure == BatchFailure.UNAVAILABLE) {
                    throw failed(attempt, "remained unavailable after " + tries
                            + " attempt(s)", error);
                }
                break; // TOO_HEAVY, or TRANSIENT with an exhausted retry budget
            }

            try {
                committer.commit(attempt.unit().descriptor(), value);
            } catch (Exception commitError) {
                running.failed("COMMIT: " + message(commitError));
                throw failed(attempt, "computed successfully but could not be committed",
                        commitError);
            }
            return RunResult.completed(running,
                    tries == 1 ? "ok" : "ok (attempt " + tries + ")");
        }

        if (attempt.depth() >= policy.maxSplitDepth()) {
            throw failed(attempt,
                    "failed and cannot be split again (depth " + attempt.depth() + ")", last);
        }
        List<? extends WorkUnit<R>> parts = attempt.unit().split();
        if (parts == null || parts.isEmpty()) {
            throw failed(attempt, "failed and cannot be split further", last);
        }
        return RunResult.split(parts);
    }

    private void initialise(
            List<? extends WorkUnit<R>> units,
            Deque<Attempt<R>> pending) {
        if (units == null || units.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        List<WorkUnit<R>> checked = new ArrayList<>(units.size());
        for (WorkUnit<R> unit : units) {
            validateUnit(unit, keys, "initial work");
            checked.add(unit);
        }
        for (int i = checked.size() - 1; i >= 0; i--) {
            pending.addFirst(new Attempt<>(checked.get(i), 0));
        }
    }

    private void restore(
            BatchCheckpoint checkpoint,
            WorkUnitFactory<R> restorer,
            Deque<Attempt<R>> pending,
            Set<String> knownKeys) throws Exception {
        knownKeys.addAll(checkpoint.knownKeys());
        Set<String> keys = new HashSet<>();
        List<Attempt<R>> restored = new ArrayList<>();
        for (BatchCheckpoint.PendingWork saved : checkpoint.pending()) {
            if (!knownKeys.contains(saved.descriptor().key())) {
                throw new IllegalStateException("Checkpoint does not know pending key: "
                        + saved.descriptor().key());
            }
            WorkUnit<R> unit = Objects.requireNonNull(restorer.restore(saved.descriptor()),
                    "WorkUnitFactory returned null for " + saved.descriptor().key());
            if (!saved.descriptor().equals(unit.descriptor())) {
                throw new IllegalStateException("Restored descriptor differs from checkpoint for "
                        + saved.descriptor().key());
            }
            validateUnit(unit, keys, "checkpoint");
            restored.add(new Attempt<>(unit, saved.splitDepth()));
        }
        for (int i = restored.size() - 1; i >= 0; i--) {
            pending.addFirst(restored.get(i));
        }
    }

    private List<WorkUnit<R>> validateSplit(
            Attempt<R> parent,
            List<? extends WorkUnit<R>> rawParts,
            Set<String> knownKeys) {
        Set<String> keys = new HashSet<>(knownKeys);

        List<WorkUnit<R>> parts = new ArrayList<>(rawParts.size());
        for (WorkUnit<R> part : rawParts) {
            validateUnit(part, keys, "split of " + parent.unit().key());
            parts.add(part);
        }
        return parts;
    }

    private static <R> void validateUnit(
            WorkUnit<R> unit,
            Set<String> knownKeys,
            String source) {
        if (unit == null) {
            throw new IllegalArgumentException(source + " contains a null work unit");
        }
        WorkDescriptor descriptor = Objects.requireNonNull(
                unit.descriptor(), source + " contains a unit with no descriptor");
        if (!knownKeys.add(descriptor.key())) {
            throw new IllegalArgumentException(
                    source + " contains duplicate work key: " + descriptor.key());
        }
    }

    private static <R> List<BatchCheckpoint.PendingWork> pendingSnapshot(
            Deque<Attempt<R>> pending) {
        return pending.stream()
                .map(a -> new BatchCheckpoint.PendingWork(a.unit().descriptor(), a.depth()))
                .toList();
    }

    private static void pause(long millis) throws InterruptedException {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static long multiplyWithoutOverflow(long value, int multiplier) {
        try {
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static void restoreInterruptIfNeeded(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String message(Throwable error) {
        if (error == null) {
            return "no error recorded";
        }
        String text = error.getMessage();
        return text == null || text.isBlank()
                ? error.getClass().getSimpleName()
                : text;
    }

    private static <R> BatchFailedException failed(
            Attempt<R> attempt,
            String reason,
            Throwable cause) {
        return new BatchFailedException(
                "Batch \"" + attempt.unit().title() + "\" " + reason + ": "
                        + message(cause), cause);
    }

    private record Attempt<R>(WorkUnit<R> unit, int depth) { }

    private record RunResult<R>(
            boolean completed,
            List<? extends WorkUnit<R>> parts,
            BatchProgress.Running running,
            String summary) {
        static <R> RunResult<R> completed(BatchProgress.Running running, String summary) {
            return new RunResult<>(true, List.of(), running, summary);
        }

        static <R> RunResult<R> split(List<? extends WorkUnit<R>> parts) {
            return new RunResult<>(false, parts, null, null);
        }
    }

    /** A unit that could not be completed without making partial work look complete. */
    public static final class BatchFailedException extends Exception {
        public BatchFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
