package batch;

import org.junit.jupiter.api.Test;
import process.CancellationToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchExecutorTest {
    private static final class TransientFailure extends Exception { }
    private static final class TooHeavyFailure extends Exception { }
    private static final class UnavailableFailure extends Exception { }

    private static final FailureClassifier CLASSIFIER = error -> {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException
                    || t instanceof java.util.concurrent.CancellationException) {
                return FailureDecision.of(BatchFailure.CANCELLED);
            }
            if (t instanceof TransientFailure) {
                return FailureDecision.of(BatchFailure.TRANSIENT);
            }
            if (t instanceof TooHeavyFailure) {
                return FailureDecision.of(BatchFailure.TOO_HEAVY);
            }
            if (t instanceof UnavailableFailure) {
                return FailureDecision.of(BatchFailure.UNAVAILABLE);
            }
        }
        return FailureDecision.of(BatchFailure.FATAL);
    };

    private static final class FakeUnit implements WorkUnit<List<String>> {
        private final List<String> ids;
        private final AtomicInteger runs;
        private final Function<FakeUnit, Exception> failure;

        FakeUnit(List<String> ids, AtomicInteger runs, Function<FakeUnit, Exception> failure) {
            this.ids = List.copyOf(ids);
            this.runs = runs;
            this.failure = failure;
        }

        @Override
        public WorkDescriptor descriptor() {
            return new WorkDescriptor(
                    "fake", String.join(",", ids), "unit[" + String.join(",", ids) + "]",
                    Map.of("ids", String.join(",", ids)));
        }

        @Override
        public List<String> execute() throws Exception {
            runs.incrementAndGet();
            Exception error = failure.apply(this);
            if (error != null) {
                throw error;
            }
            return ids;
        }

        @Override
        public List<WorkUnit<List<String>>> split() {
            if (ids.size() < 2) {
                return List.of();
            }
            int middle = ids.size() / 2;
            return List.of(
                    new FakeUnit(ids.subList(0, middle), runs, failure),
                    new FakeUnit(ids.subList(middle, ids.size()), runs, failure));
        }

        int size() {
            return ids.size();
        }
    }

    private static BatchExecutor<List<String>> executor(BatchPolicy policy) {
        return new BatchExecutor<>(policy, BatchProgress.NOOP, CLASSIFIER,
                new CancellationToken(), BatchCheckpointStore.NONE);
    }

    @Test
    void transientFailureRetriesTheUnchangedUnit() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        List<String> committed = new ArrayList<>();
        FakeUnit unit = new FakeUnit(List.of("a", "b"), runs,
                ignored -> runs.get() == 1 ? new TransientFailure() : null);

        executor(new BatchPolicy(3, 7, 0, false))
                .run(List.of(unit), (descriptor, result) -> committed.addAll(result));

        assertEquals(2, runs.get());
        assertEquals(List.of("a", "b"), committed);
    }

    @Test
    void tooHeavyFailureSplitsWithoutRetrying() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        List<String> committed = new ArrayList<>();
        FakeUnit unit = new FakeUnit(List.of("a", "b"), runs,
                candidate -> candidate.size() > 1 ? new TooHeavyFailure() : null);

        executor(new BatchPolicy(3, 7, 0, false))
                .run(List.of(unit), (descriptor, result) -> committed.addAll(result));

        assertEquals(3, runs.get());
        assertEquals(List.of("a", "b"), committed);
    }

    @Test
    void unavailableFailureRetriesButNeverSplits() {
        AtomicInteger runs = new AtomicInteger();
        FakeUnit unit = new FakeUnit(List.of("a", "b"), runs,
                ignored -> new UnavailableFailure());

        BatchExecutor.BatchFailedException error = assertThrows(
                BatchExecutor.BatchFailedException.class,
                () -> executor(new BatchPolicy(3, 7, 0, false)).run(List.of(unit)));

        // Two, not three: the 4-arg policy defaults UNAVAILABLE to its own smaller
        // budget, because the transport has already retried this failure before the
        // executor sees it. Splitting is still never attempted.
        assertEquals(2, runs.get());
        assertTrue(error.getMessage().contains("remained unavailable"));
    }

    @Test
    void unavailableUsesItsOwnSmallerBudgetNotTheTransientOne() {
        AtomicInteger runs = new AtomicInteger();
        FakeUnit unit = new FakeUnit(List.of("a", "b"), runs,
                ignored -> new UnavailableFailure());

        // maxAttempts 5 for TRANSIENT, but UNAVAILABLE gets 2: the SPARQL transport has
        // already retried 429/5xx three times before the executor sees it, so a shared
        // budget would mean up to fifteen requests at an overloaded endpoint.
        BatchPolicy policy = new BatchPolicy(5, 7, 0, false, 2);
        assertThrows(BatchExecutor.BatchFailedException.class,
                () -> executor(policy).run(List.of(unit)));

        assertEquals(2, runs.get(), "UNAVAILABLE stops at its own budget");
    }

    @Test
    void transientStillUsesTheFullBudgetAlongsideIt() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        List<String> committed = new ArrayList<>();
        FakeUnit unit = new FakeUnit(List.of("a", "b"), runs,
                ignored -> runs.get() < 4 ? new TransientFailure() : null);

        executor(new BatchPolicy(5, 7, 0, false, 2))
                .run(List.of(unit), (descriptor, result) -> committed.addAll(result));

        assertEquals(4, runs.get(), "TRANSIENT keeps maxAttempts, unaffected by the "
                + "smaller UNAVAILABLE budget");
        assertEquals(List.of("a", "b"), committed);
    }

    @Test
    void theNeutralClassifierCanStillReachTheRetryPath() {
        // A caller on any other transport must be able to express "the response stopped
        // before the payload did" — the shape a truncated 200 has.
        assertEquals(BatchFailure.TRANSIENT, FailureClassifier.standard()
                .classify(new java.io.EOFException("stream ended")).failure());
        assertEquals(BatchFailure.UNAVAILABLE, FailureClassifier.standard()
                .classify(new java.io.IOException("connection refused")).failure());
        assertEquals(BatchFailure.TOO_HEAVY, FailureClassifier.standard()
                .classify(new java.net.http.HttpTimeoutException("timed out")).failure());
    }

    @Test
    void fatalFailureRunsOnceAndNeverSplits() {
        AtomicInteger runs = new AtomicInteger();
        FakeUnit unit = new FakeUnit(List.of("a", "b"), runs,
                ignored -> new IllegalStateException("bad query"));

        BatchExecutor.BatchFailedException error = assertThrows(
                BatchExecutor.BatchFailedException.class,
                () -> executor(new BatchPolicy(3, 7, 0, false)).run(List.of(unit)));

        assertEquals(1, runs.get());
        assertTrue(error.getMessage().contains("fatal"));
    }

    @Test
    void aFailedAttemptPublishesNoPartialResult() {
        AtomicInteger runs = new AtomicInteger();
        List<String> committed = new ArrayList<>();
        FakeUnit unit = new FakeUnit(List.of("a"), runs, ignored -> new TooHeavyFailure());

        assertThrows(BatchExecutor.BatchFailedException.class,
                () -> executor(new BatchPolicy(1, 7, 0, false)).run(
                        List.of(unit), (descriptor, result) -> committed.addAll(result)));

        assertTrue(committed.isEmpty());
    }

    @Test
    void aCommitFailureDoesNotRetryOrSplitTheNetworkWork() {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        FakeUnit unit = new FakeUnit(List.of("a", "b"), runs, ignored -> null);

        BatchExecutor.BatchFailedException error = assertThrows(
                BatchExecutor.BatchFailedException.class,
                () -> executor(new BatchPolicy(3, 7, 0, false)).run(
                        List.of(unit), (descriptor, result) -> {
                            commits.incrementAndGet();
                            throw new IllegalStateException("registry rejected result");
                        }));

        assertEquals(1, runs.get());
        assertEquals(1, commits.get());
        assertTrue(error.getMessage().contains("could not be committed"));
    }

    @Test
    void splitDepthIsBounded() {
        AtomicInteger runs = new AtomicInteger();
        FakeUnit unit = new FakeUnit(List.of("a", "b", "c", "d"), runs,
                ignored -> new TooHeavyFailure());

        BatchExecutor.BatchFailedException error = assertThrows(
                BatchExecutor.BatchFailedException.class,
                () -> executor(new BatchPolicy(1, 1, 0, false)).run(List.of(unit)));

        assertTrue(error.getMessage().contains("split again"));
    }

    @Test
    void existingCancellationTokenStopsBeforeWorkStarts() {
        CancellationToken cancellation = new CancellationToken();
        cancellation.cancel();
        AtomicInteger runs = new AtomicInteger();
        BatchExecutor<List<String>> executor = new BatchExecutor<>(
                BatchPolicy.defaults(), BatchProgress.NOOP, CLASSIFIER,
                cancellation, BatchCheckpointStore.NONE);

        assertThrows(java.util.concurrent.CancellationException.class,
                () -> executor.run(List.of(new FakeUnit(List.of("a"), runs, ignored -> null))));
        assertEquals(0, runs.get());
    }

    @Test
    void workInterruptionIsPropagatedAndRestoresTheFlag() {
        AtomicInteger runs = new AtomicInteger();
        FakeUnit unit = new FakeUnit(List.of("a"), runs,
                ignored -> new InterruptedException("cancelled"));

        assertThrows(InterruptedException.class,
                () -> executor(new BatchPolicy(3, 7, 0, false)).run(List.of(unit)));
        assertEquals(1, runs.get());
        assertTrue(Thread.interrupted());
    }

    @Test
    void interruptionDuringBackoffRestoresTheFlag() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicBoolean flagAtExit = new AtomicBoolean();
        WorkUnit<Void> unit = new WorkUnit<>() {
            @Override public WorkDescriptor descriptor() {
                return new WorkDescriptor("test", "backoff", "backoff");
            }
            @Override public Void execute() throws Exception {
                attempted.countDown();
                throw new TransientFailure();
            }
            @Override public List<? extends WorkUnit<Void>> split() { return List.of(); }
        };
        BatchExecutor<Void> executor = new BatchExecutor<>(
                new BatchPolicy(3, 0, 30_000, false), BatchProgress.NOOP,
                CLASSIFIER, new CancellationToken(), BatchCheckpointStore.NONE);

        Thread thread = new Thread(() -> {
            try {
                executor.run(List.of(unit));
            } catch (InterruptedException expected) {
                flagAtExit.set(Thread.currentThread().isInterrupted());
            } catch (Exception unexpected) {
                throw new AssertionError(unexpected);
            }
        });
        thread.start();
        attempted.await();
        thread.interrupt();
        thread.join(2_000);

        assertFalse(thread.isAlive());
        assertTrue(flagAtExit.get());
    }

    @Test
    void duplicateInitialAndSplitKeysAreRejected() {
        AtomicInteger runs = new AtomicInteger();
        FakeUnit same = new FakeUnit(List.of("a"), runs, ignored -> null);
        assertThrows(IllegalArgumentException.class,
                () -> executor(new BatchPolicy(1, 1, 0, false)).run(List.of(same, same)));

        WorkUnit<Void> invalidSplit = new WorkUnit<>() {
            @Override public WorkDescriptor descriptor() {
                return new WorkDescriptor("test", "parent", "parent");
            }
            @Override public Void execute() throws Exception { throw new TooHeavyFailure(); }
            @Override public List<? extends WorkUnit<Void>> split() { return List.of(this); }
        };
        BatchExecutor<Void> voidExecutor = new BatchExecutor<>(
                new BatchPolicy(1, 1, 0, false), BatchProgress.NOOP,
                CLASSIFIER, new CancellationToken(), BatchCheckpointStore.NONE);
        assertThrows(IllegalArgumentException.class,
                () -> voidExecutor.run(List.of(invalidSplit)));
    }

    @Test
    void resumeRestoresTheSavedAdaptiveLeafQueue() throws Exception {
        MemoryCheckpointStore checkpoints = new MemoryCheckpointStore();
        AtomicInteger parentRuns = new AtomicInteger();
        AtomicInteger aRuns = new AtomicInteger();
        AtomicInteger bRuns = new AtomicInteger();
        AtomicBoolean bMustFail = new AtomicBoolean(true);
        Map<String, Integer> commits = new LinkedHashMap<>();

        WorkUnit<String> parent = partition("parent", parentRuns,
                () -> { throw new TooHeavyFailure(); },
                List.of(
                        partition("a", aRuns, () -> "A", List.of()),
                        partition("b", bRuns, () -> {
                            if (bMustFail.getAndSet(false)) {
                                throw new IllegalStateException("stop here");
                            }
                            return "B";
                        }, List.of())));
        Map<String, WorkUnit<String>> restorable = Map.of(
                "a", partition("a", aRuns, () -> "A", List.of()),
                "b", partition("b", bRuns, () -> "B", List.of()));
        ResultCommitter<String> committer = (descriptor, value) ->
                commits.merge(descriptor.key(), 1, Integer::sum);

        BatchExecutor<String> first = new BatchExecutor<>(
                new BatchPolicy(1, 7, 0, true), BatchProgress.NOOP,
                CLASSIFIER, new CancellationToken(), checkpoints);
        assertThrows(BatchExecutor.BatchFailedException.class,
                () -> first.run("adaptive", List.of(parent),
                        descriptor -> restorable.get(descriptor.key()), committer));

        assertEquals(List.of("b"), checkpoints.checkpoint.pending().stream()
                .map(p -> p.descriptor().key()).toList());
        assertEquals(1, commits.get("a"));

        BatchExecutor<String> second = new BatchExecutor<>(
                new BatchPolicy(1, 7, 0, true), BatchProgress.NOOP,
                CLASSIFIER, new CancellationToken(), checkpoints);
        second.run("adaptive", List.of(parent),
                descriptor -> restorable.get(descriptor.key()), committer);

        assertEquals(1, parentRuns.get(), "the parent is not reconstructed and split again");
        assertEquals(1, aRuns.get(), "the completed leaf is not replayed");
        assertEquals(2, bRuns.get());
        assertEquals(Map.of("a", 1, "b", 1), commits);
        assertTrue(checkpoints.deleted);
    }

    @Test
    void aStopBetweenCommitAndCheckpointRequiresOnlyIdempotentReplay() throws Exception {
        FailingCheckpointStore checkpoints = new FailingCheckpointStore(2);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger commitCalls = new AtomicInteger();
        Map<String, String> published = new LinkedHashMap<>();
        WorkUnit<String> unit = partition("a", runs, () -> "A", List.of());
        ResultCommitter<String> committer = (descriptor, value) -> {
            commitCalls.incrementAndGet();
            published.put(descriptor.key(), value);
        };

        BatchExecutor<String> first = new BatchExecutor<>(
                new BatchPolicy(1, 7, 0, true), BatchProgress.NOOP,
                CLASSIFIER, new CancellationToken(), checkpoints);
        assertThrows(java.io.IOException.class,
                () -> first.run("commit-window", List.of(unit), ignored -> unit, committer));

        checkpoints.failOnSave = -1;
        BatchExecutor<String> second = new BatchExecutor<>(
                new BatchPolicy(1, 7, 0, true), BatchProgress.NOOP,
                CLASSIFIER, new CancellationToken(), checkpoints);
        second.run("commit-window", List.of(unit), ignored -> unit, committer);

        assertEquals(2, runs.get());
        assertEquals(2, commitCalls.get(), "the commit window can replay once");
        assertEquals(Map.of("a", "A"), published,
                "an idempotent identity-keyed commit keeps one published result");
    }

    private static <R> WorkUnit<R> partition(
            String key,
            AtomicInteger runs,
            ThrowingSupplier<R> execution,
            List<? extends WorkUnit<R>> parts) {
        return new WorkUnit<>() {
            @Override public WorkDescriptor descriptor() {
                return new WorkDescriptor("partition", key, key);
            }
            @Override public R execute() throws Exception {
                runs.incrementAndGet();
                return execution.get();
            }
            @Override public List<? extends WorkUnit<R>> split() { return parts; }
        };
    }

    @FunctionalInterface
    private interface ThrowingSupplier<R> {
        R get() throws Exception;
    }

    private static final class MemoryCheckpointStore implements BatchCheckpointStore {
        BatchCheckpoint checkpoint;
        boolean deleted;

        @Override public Optional<BatchCheckpoint> load(String runKey) {
            return Optional.ofNullable(checkpoint);
        }
        @Override public void save(BatchCheckpoint checkpoint) {
            this.checkpoint = checkpoint;
        }
        @Override public void delete(String runKey) {
            deleted = true;
            checkpoint = null;
        }
    }

    private static final class FailingCheckpointStore implements BatchCheckpointStore {
        BatchCheckpoint checkpoint;
        int saveCalls;
        int failOnSave;

        FailingCheckpointStore(int failOnSave) {
            this.failOnSave = failOnSave;
        }

        @Override public Optional<BatchCheckpoint> load(String runKey) {
            return Optional.ofNullable(checkpoint);
        }

        @Override public void save(BatchCheckpoint checkpoint) throws Exception {
            saveCalls++;
            if (saveCalls == failOnSave) {
                throw new java.io.IOException("simulated stop before checkpoint replace");
            }
            this.checkpoint = checkpoint;
        }

        @Override public void delete(String runKey) {
            checkpoint = null;
        }
    }
}
