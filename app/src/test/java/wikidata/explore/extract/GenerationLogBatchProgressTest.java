package wikidata.explore.extract;

import batch.BatchProgress;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one adapter from a log to the shared adaptive executor, so every batched load
 * renders one batch lifecycle the same way.
 *
 * <p>Both halves shipped broken once. `BatchProgress` gives `detail` and `message`
 * defaults that do nothing, so an adapter written as a lambda — or hand-rolled at a call
 * site with only `done`/`failed` — compiles, runs, and silently drops the executor's
 * account of itself: why a batch was retried, why it was split, how long the whole run
 * took. Nothing else reports those, so losing them leaves no trace at all.
 */
class GenerationLogBatchProgressTest {

    @Test void theExecutorsBetweenUnitMessagesReachTheLog() {
        Recording log = new Recording();

        log.batchProgress().message("Batch execution wall time: 909 ms");

        assertEquals(List.of("Batch execution wall time: 909 ms"), log.messages,
                "a message the executor emits between units must reach the log; "
                        + "BatchProgress.message defaults to discarding it");
    }

    @Test void anAttemptsRetryAndSplitDecisionsAreKeptWithItsOutcome() {
        Recording log = new Recording();
        BatchProgress.Running running =
                log.batchProgress().started("Fetch statements", "SELECT …");

        running.detail("Attempt 1/3 failed: ResponseTimeoutException");
        running.detail("Retry budget exhausted; splitting into smaller batches");
        running.adapted("Adapted into 2 smaller batch(es)");

        assertEquals(1, log.completed.size());
        String reported = log.completed.get(0);
        assertTrue(reported.contains("Attempt 1/3 failed"), reported);
        assertTrue(reported.contains("splitting into smaller batches"), reported);
        assertTrue(reported.contains("Adapted into 2 smaller batch(es)"), reported);
    }

    @Test void aFailureKeepsTheAttemptsThatLedToIt() {
        Recording log = new Recording();
        BatchProgress.Running running = log.batchProgress().started("Fetch", "SELECT …");

        running.detail("Attempt 1/3 failed: 429");
        running.failed("Gave up");

        assertEquals(1, log.failures.size());
        assertTrue(log.failures.get(0).contains("Attempt 1/3 failed: 429"),
                log.failures.get(0));
    }

    private static final class Recording implements GenerationLog {
        private final List<String> messages = new ArrayList<>();
        private final List<String> completed = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();

        @Override public void message(String text) { messages.add(text); }

        @Override public void subquery(String title, String request, String summary) {
            completed.add(summary);
        }

        @Override public void subqueryFailed(String title, String request, String error) {
            failures.add(error);
        }

        @Override public Running subqueryStarted(String title, String request) {
            return new Running() {
                @Override public void done(String summary) { completed.add(summary); }
                @Override public void failed(String error) { failures.add(error); }
            };
        }
    }
}
