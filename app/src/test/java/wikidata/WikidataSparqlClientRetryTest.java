package wikidata;

import org.junit.jupiter.api.Test;
import wikidata.WikidataSparqlClient.SparqlHttpException;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retry decision + backoff are pure functions of the failure and the
 * attempts left, so they can be checked without touching the network.
 */
class WikidataSparqlClientRetryTest {

    @Test
    void throttlingAndServerErrorsAreRetryable() {
        assertTrue(WikidataSparqlClient.isRetryableStatus(429));
        assertTrue(WikidataSparqlClient.isRetryableStatus(500));
        assertTrue(WikidataSparqlClient.isRetryableStatus(502));
        assertTrue(WikidataSparqlClient.isRetryableStatus(503));
        assertTrue(WikidataSparqlClient.isRetryableStatus(504));
    }

    @Test
    void clientErrorsAreNotRetryable() {
        assertFalse(WikidataSparqlClient.isRetryableStatus(400));
        assertFalse(WikidataSparqlClient.isRetryableStatus(404));
    }

    @Test
    void a429IsRetriedButAMalformedQueryIsNot() {
        assertTrue(WikidataSparqlClient.shouldRetry(
                new SparqlHttpException(429, 6000, "slow down"), 3));
        assertFalse(WikidataSparqlClient.shouldRetry(
                new SparqlHttpException(400, -1, "bad query"), 3));
    }

    @Test
    void transientNetworkErrorsAreRetriedTimeoutsAndCancellationAreNot() {
        assertTrue(WikidataSparqlClient.shouldRetry(new IOException("reset"), 3));
        assertFalse(WikidataSparqlClient.shouldRetry(new HttpTimeoutException("t"), 3));
        assertFalse(WikidataSparqlClient.shouldRetry(new CancellationException(), 3));
    }

    @Test
    void theLastAttemptNeverRetries() {
        assertFalse(WikidataSparqlClient.shouldRetry(new IOException("reset"), 1));
    }

    @Test
    void aTruncatedPartial200IsRetried() {
        // Measured: a batch that truncated mid-run returned 317 rows in 1-2s when
        // re-issued verbatim, twice. A truncated body is WDQS closing the response
        // stream early under pressure, not a query that cannot finish — and one
        // that cannot finish fails identically each attempt and exhausts the budget.
        assertTrue(WikidataSparqlClient.shouldRetry(
                new WikidataSparqlClient.TruncatedResponseException("truncated", null), 3));
        assertTrue(WikidataSparqlClient.shouldRetry(
                new CompletionException(
                        new WikidataSparqlClient.TruncatedResponseException("t", null)), 3));
    }

    @Test
    void aTruncatedPartial200StillStopsAtTheLastAttempt() {
        assertFalse(WikidataSparqlClient.shouldRetry(
                new WikidataSparqlClient.TruncatedResponseException("truncated", null), 1));
    }

    @Test
    void aClientSideTimeoutIsStillNotRetried() {
        // Here the 60s wall was actually hit, so a verbatim re-issue burns another
        // 60s for the same result — unlike a truncation, which returns in seconds.
        assertFalse(WikidataSparqlClient.shouldRetry(
                new java.net.http.HttpTimeoutException("request timed out"), 3));
    }

    @Test
    void theFailureIsFoundThroughACompletionExceptionWrapper() {
        // The async pipeline wraps thrown errors in a CompletionException.
        Throwable wrapped =
                new CompletionException(new SparqlHttpException(400, -1, "bad"));
        assertFalse(WikidataSparqlClient.shouldRetry(wrapped, 3));
    }

    @Test
    void retryAfterWinsOverLinearBackoffWhenPresent() {
        assertEquals(6000L, WikidataSparqlClient.backoffMillis(
                new SparqlHttpException(429, 6000, ""), 3));
    }

    @Test
    void linearBackoffGrowsByAttemptWhenNoRetryAfter() {
        assertEquals(1000L, WikidataSparqlClient.backoffMillis(
                new SparqlHttpException(503, -1, ""), 3));
        assertEquals(2000L, WikidataSparqlClient.backoffMillis(new IOException("x"), 2));
    }
}
