package batch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The escalation, pinned without a network: retry what may yet succeed, narrow what
 * cannot, fail rather than return half the work.
 */
class BatchExecutorTest {

    /** A unit over a list of ids that fails according to a scripted rule. */
    private static final class FakeUnit implements WorkUnit {
        private final List<String> ids;
        private final List<String> completed;
        private final AtomicInteger runs;
        private final java.util.function.Predicate<FakeUnit> failWhen;
        private final java.util.function.Supplier<Exception> error;

        FakeUnit(List<String> ids, List<String> completed, AtomicInteger runs,
                 java.util.function.Predicate<FakeUnit> failWhen,
                 java.util.function.Supplier<Exception> error) {
            this.ids = ids;
            this.completed = completed;
            this.runs = runs;
            this.failWhen = failWhen;
            this.error = error;
        }

        int size() { return ids.size(); }

        @Override public String key() { return String.join(",", ids); }
        @Override public String title() { return "unit[" + key() + "]"; }

        @Override public void run() throws Exception {
            runs.incrementAndGet();
            if (failWhen.test(this)) {
                throw error.get();
            }
            completed.addAll(ids);
        }

        @Override public List<WorkUnit> split() {
            if (ids.size() < 2) {
                return List.of();
            }
            int mid = ids.size() / 2;
            return List.of(
                    new FakeUnit(ids.subList(0, mid), completed, runs, failWhen, error),
                    new FakeUnit(ids.subList(mid, ids.size()), completed, runs, failWhen, error));
        }
    }

    // Named to match what BatchFailure.classify keys on.
    private static final class TruncatedResponseException extends RuntimeException {
        TruncatedResponseException() { super("body does not end in '}'"); }
    }

    private static final class HttpTimeoutException extends RuntimeException {
        HttpTimeoutException() { super("request timed out"); }
    }

    private static BatchExecutor executor(BatchPolicy policy) {
        return new BatchExecutor(policy, BatchProgress.NOOP);
    }

    @Test void aTransientFailureIsRetriedUnchangedRatherThanSplit() throws Exception {
        List<String> done = new ArrayList<>();
        AtomicInteger runs = new AtomicInteger();
        // Fails once, succeeds on the retry — the measured truncation case.
        FakeUnit unit = new FakeUnit(List.of("a", "b", "c", "d"), done, runs,
                                     u -> runs.get() == 1,
                                     TruncatedResponseException::new);

        executor(new BatchPolicy(3, 6, 0L, false)).run(List.of(unit));

        assertEquals(List.of("a", "b", "c", "d"), done,
                     "the whole unit completes on retry");
        assertEquals(2, runs.get(), "one failure, one retry — no split");
    }

    @Test void aTooHeavyFailureSplitsImmediatelyWithoutBurningRetries() throws Exception {
        List<String> done = new ArrayList<>();
        AtomicInteger runs = new AtomicInteger();
        // Anything bigger than one id times out; singles succeed.
        FakeUnit unit = new FakeUnit(List.of("a", "b"), done, runs,
                                     u -> u.size() > 1,
                                     HttpTimeoutException::new);

        executor(new BatchPolicy(3, 6, 0L, false)).run(List.of(unit));

        assertEquals(2, done.size(), "both ids complete via the halves");
        assertEquals(3, runs.get(),
                     "the pair once (not three times) plus each half: a timeout must not "
                             + "spend the retry budget re-issuing the same wall-clock");
    }

    @Test void aUnitThatCannotBeSplitAnyFurtherFailsTheRun() {
        List<String> done = new ArrayList<>();
        AtomicInteger runs = new AtomicInteger();
        FakeUnit unit = new FakeUnit(List.of("only"), done, runs,
                                     u -> true, HttpTimeoutException::new);

        BatchExecutor.BatchFailedException failure = assertThrows(
                BatchExecutor.BatchFailedException.class,
                () -> executor(new BatchPolicy(2, 6, 0L, false)).run(List.of(unit)));

        assertTrue(failure.getMessage().contains("cannot be split further"),
                   failure.getMessage());
        assertTrue(done.isEmpty(), "nothing is reported as done");
    }

    @Test void theSplitDepthBoundStopsTheRecursion() {
        List<String> done = new ArrayList<>();
        AtomicInteger runs = new AtomicInteger();
        FakeUnit unit = new FakeUnit(List.of("a", "b", "c", "d"), done, runs,
                                     u -> true, HttpTimeoutException::new);

        BatchExecutor.BatchFailedException failure = assertThrows(
                BatchExecutor.BatchFailedException.class,
                () -> executor(new BatchPolicy(1, 1, 0L, false)).run(List.of(unit)));

        assertTrue(failure.getMessage().contains("split again"), failure.getMessage());
    }

    @Test void aPartialRunFailsInsteadOfReturningWhatItManaged() {
        List<String> done = new ArrayList<>();
        AtomicInteger runs = new AtomicInteger();
        FakeUnit good = new FakeUnit(List.of("a"), done, runs, u -> false,
                                     HttpTimeoutException::new);
        FakeUnit bad = new FakeUnit(List.of("b"), done, runs, u -> true,
                                    HttpTimeoutException::new);

        assertThrows(BatchExecutor.BatchFailedException.class,
                     () -> executor(new BatchPolicy(1, 0, 0L, false))
                             .run(List.of(good, bad)));

        // The successful unit's work is still the caller's — it is simply not passed
        // off as a complete run.
        assertEquals(List.of("a"), done);
    }

    @Test void cancellationStopsImmediatelyAndIsNeverRetried() {
        AtomicInteger runs = new AtomicInteger();
        FakeUnit unit = new FakeUnit(List.of("a", "b"), new ArrayList<>(), runs,
                                     u -> true,
                                     () -> new InterruptedException("cancelled"));

        assertThrows(InterruptedException.class,
                     () -> executor(new BatchPolicy(3, 6, 0L, false)).run(List.of(unit)));
        assertEquals(1, runs.get(), "a cancel is obeyed at once");
        assertTrue(Thread.interrupted(), "the interrupt flag is restored for the caller");
    }

    @Test void classificationKeysOnTheCauseChainNotJustTheTopException() {
        assertEquals(BatchFailure.TRANSIENT, BatchFailure.classify(
                new java.util.concurrent.CompletionException(
                        new TruncatedResponseException())));
        assertEquals(BatchFailure.TOO_HEAVY, BatchFailure.classify(
                new RuntimeException("wrapped", new HttpTimeoutException())));
        assertEquals(BatchFailure.CANCELLED, BatchFailure.classify(
                new RuntimeException("wrapped", new InterruptedException())));
        assertEquals(BatchFailure.FATAL, BatchFailure.classify(
                new IllegalStateException("something else entirely")));
    }
}
