package wikidata;

import batch.BatchFailure;
import batch.FailureDecision;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WikidataBatchFailureClassifierTest {
    private final WikidataBatchFailureClassifier classifier =
            WikidataBatchFailureClassifier.INSTANCE;

    @Test
    void distinguishesClientErrorsPressureAndQueryWeight() {
        assertEquals(BatchFailure.FATAL, classify(http(400, -1)).failure());
        assertEquals(BatchFailure.FATAL, classify(http(403, -1)).failure());
        assertEquals(BatchFailure.UNAVAILABLE, classify(http(429, 12_000)).failure());
        assertEquals(12_000, classify(http(429, 12_000)).retryAfterMillis());
        assertEquals(BatchFailure.UNAVAILABLE, classify(http(503, -1)).failure());
        assertEquals(BatchFailure.TOO_HEAVY,
                classify(new HttpTimeoutException("60 seconds")).failure());
        assertEquals(BatchFailure.TRANSIENT,
                classify(new WikidataSparqlClient.TruncatedResponseException(
                        "partial JSON", new IllegalStateException())).failure());
    }

    @Test
    void walksWrapperCauses() {
        assertEquals(BatchFailure.FATAL,
                classify(new CompletionException(http(401, -1))).failure());
    }

    private FailureDecision classify(Throwable error) {
        return classifier.classify(error);
    }

    private static WikidataSparqlClient.SparqlHttpException http(int status, long retryAfter) {
        return new WikidataSparqlClient.SparqlHttpException(status, retryAfter, "test");
    }
}
