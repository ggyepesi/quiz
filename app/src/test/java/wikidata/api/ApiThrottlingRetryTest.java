package wikidata.api;

import com.fasterxml.jackson.databind.JsonNode;
import objectview.utils.RetryAfter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Throttling is waited out, not given up on.
 *
 * <p>The client threw a bare IOException for every failure, so its retry could not tell
 * a 429 from a 404 and gave both three attempts half a second apart. A Wikimedia
 * throttling window outlasts that easily: in a 20,000-member run, 54 consecutive label
 * batches were refused with 429, every attempt burned inside the same window, and 2,700
 * references were left named by their QID.
 */
class ApiThrottlingRetryTest {

    @Test void aRetryAfterHeaderDecidesTheWait() {
        ApiHttpException throttled = new ApiHttpException(429, 30_000, "429", null);

        assertEquals(30_000, WikidataApiClient.retryWaitMillis(throttled, 1),
                     "the server said how long to wait; the client does not second-guess it");
    }

    /**
     * Without a header the backoff must be able to outlast a window rather than a
     * hiccup. The old fixed 500ms x attempt spent its entire budget in three seconds,
     * which is why every attempt landed inside the same throttle.
     */
    @Test void theBackoffGrowsEnoughToSitOutAWindow() {
        List<Long> waits = new ArrayList<>();
        for (int attempt = 1; attempt <= 4; attempt++) {
            waits.add(WikidataApiClient.retryWaitMillis(
                    new ApiHttpException(429, -1, "429", null), attempt));
        }

        assertEquals(List.of(1000L, 2000L, 4000L, 8000L), waits);
        assertTrue(waits.stream().mapToLong(Long::longValue).sum() >= 15_000,
                   "the old policy waited 3s in total and gave up inside the window");
    }

    /** A connection-level failure has no status and still backs off — it must not be
     *  mistaken for a permanent error and dropped after one try. */
    @Test void aFailureWithNoStatusStillBacksOff() {
        assertEquals(1000L, WikidataApiClient.retryWaitMillis(
                new java.io.IOException("connection reset"), 1));
    }

    @Test void aStatusTheServerWillNotReconsiderIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        WikidataApiClient client = new WikidataApiClient("test") {
            @Override
            protected JsonNode getEntitiesBatchWithRetry(
                    List<String> qids, boolean withClaims) throws Exception {
                attempts.incrementAndGet();
                throw new ApiHttpException(404, -1, "not found", null);
            }
        };

        assertThrows(Exception.class,
                     () -> client.getEntities(List.of("Q1"), List.of(), null));
        assertEquals(1, attempts.get(),
                     "retrying a permanent error only lengthens a run that will fail");
    }

    /** One list of retryable statuses, shared with the SPARQL client, so the two
     *  cannot disagree about what throttling looks like — which is how one came to
     *  honour Retry-After while the other gave up after three half-second waits. */
    @Test void throttlingAndTransientServerErrorsAreTheRetryableOnes() {
        assertTrue(RetryAfter.isRetryableStatus(429));
        assertTrue(RetryAfter.isRetryableStatus(503));
        assertTrue(RetryAfter.isRetryableStatus(504));
        assertFalse(RetryAfter.isRetryableStatus(404));
        assertFalse(RetryAfter.isRetryableStatus(400));
    }
}
