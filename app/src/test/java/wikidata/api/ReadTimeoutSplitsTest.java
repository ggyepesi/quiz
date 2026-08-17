package wikidata.api;

import batch.BatchFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import wikidata.WikidataBatchFailureClassifier;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A read timeout means the batch was too heavy, and the answer to too heavy is to ask
 * for less.
 *
 * <p>It was reaching the user as FATAL instead, refusing the whole run:
 * {@code wbgetentities} runs over {@code HttpURLConnection}, whose read timeout fires
 * once the HEADERS have arrived and the body has not. The connection therefore still
 * answered {@code 200}, the failure was wrapped as an HTTP outcome carrying that status,
 * and a classifier with no rule for 200 called it fatal — for a request the executor
 * already knew how to split in half.
 */
class ReadTimeoutSplitsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The exact reported shape: a body that ran out of time on a connection whose
     *  status reads 200. */
    @Test void aReadTimeoutIsNotReportedAsAnHttpOutcome() {
        IOException raised = WikidataApiClient.transportFailure(
                new SocketTimeoutException("Read timed out"), 200, -1,
                "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=Q170250");

        assertInstanceOf(batch.ResponseTimeoutException.class, raised,
                "the server reported no outcome — the body simply did not arrive");
        assertTrue(raised.getMessage().contains("wbgetentities"),
                "the request is still named in the failure: " + raised.getMessage());
    }

    @Test void aConnectionTimeoutRemainsRetryableAndDoesNotSplit() {
        IOException raised = WikidataApiClient.transportFailure(
                new SocketTimeoutException("Connect timed out"), -1, -1,
                "https://www.wikidata.org/w/api.php");

        assertInstanceOf(SocketTimeoutException.class, raised);
        assertFalse(raised instanceof batch.ResponseTimeoutException);
        assertEquals(BatchFailure.UNAVAILABLE,
                WikidataBatchFailureClassifier.INSTANCE.classify(raised).failure());
        assertTrue(WikidataApiClient.worthRetryingUnchanged((Exception) raised));
    }

    @Test void aSuccessfulHeaderFollowedByConnectionResetIsNotFatalHttp200() {
        IOException raised = WikidataApiClient.transportFailure(
                new java.net.SocketException("Connection reset"), 200, -1,
                "https://www.wikidata.org/w/api.php");

        assertFalse(raised instanceof ApiHttpException);
        assertInstanceOf(batch.ResponseInterruptedException.class, raised);
        assertEquals(BatchFailure.TRANSIENT,
                WikidataBatchFailureClassifier.INSTANCE.classify(raised).failure());
        assertFalse(WikidataApiClient.worthRetryingUnchanged((Exception) raised));
    }

    /** The status path is untouched: a real refusal keeps its status and Retry-After. */
    @Test void arefusalKeepsItsStatusAndRetryAfter() {
        ApiHttpException refused = assertInstanceOf(ApiHttpException.class,
                WikidataApiClient.transportFailure(
                        new IOException("throttled"), 429, 30_000, "https://x"));

        assertEquals(429, refused.status());
        assertEquals(30_000, refused.retryAfterMillis());
    }

    @Test void aTimeoutTellsTheExecutorToSplit() {
        assertEquals(BatchFailure.TOO_HEAVY,
                WikidataBatchFailureClassifier.INSTANCE
                        .classify(new batch.ResponseTimeoutException(
                                "Read timed out", null)).failure());
        assertEquals(BatchFailure.TOO_HEAVY,
                batch.FailureClassifier.standard()
                        .classify(new batch.ResponseTimeoutException(
                                "Read timed out", null)).failure());
    }

    /** Whereas a status nothing knows what to do with stays fatal — which is why the
     *  timeout must never be dressed up as one. */
    @Test void anUnexpectedStatusRemainsFatal() {
        assertEquals(BatchFailure.FATAL,
                WikidataBatchFailureClassifier.INSTANCE
                        .classify(new ApiHttpException(200, -1, "Read timed out", null))
                        .failure());
    }

    /** Re-asking for the same 50 entities cannot do better; only a smaller batch can. */
    @Test void aTimeoutIsNotRetriedUnchanged() {
        assertFalse(WikidataApiClient.worthRetryingUnchanged(
                new batch.ResponseTimeoutException("Read timed out", null)));
        assertTrue(WikidataApiClient.worthRetryingUnchanged(
                new SocketTimeoutException("Connect timed out")));
        assertTrue(WikidataApiClient.worthRetryingUnchanged(
                new ApiHttpException(429, -1, "throttled", null)));
        assertFalse(WikidataApiClient.worthRetryingUnchanged(
                new ApiHttpException(404, -1, "gone", null)));
    }

    /** End to end over the real fan-out: the reported 50-QID claims batch times out,
     *  and every entity still arrives — through halves the server can answer. */
    @Test void theReportedBatchStillLoadsEveryEntity() throws Exception {
        List<Integer> attempted = java.util.Collections.synchronizedList(new ArrayList<>());
        WikidataApiClient client = new WikidataApiClient("test") {
            @Override protected JsonNode getEntitiesBatchWithRetry(
                    List<String> qids, boolean withClaims, List<String> pids) throws Exception {
                attempted.add(qids.size());
                if (qids.size() > 12) {
                    throw new batch.ResponseTimeoutException("Read timed out", null);
                }
                StringBuilder entities = new StringBuilder();
                for (String qid : qids) {
                    if (!entities.isEmpty()) entities.append(',');
                    entities.append('"').append(qid).append("\":{\"id\":\"").append(qid)
                            .append("\",\"labels\":{},\"claims\":{}}");
                }
                return JSON.readTree("{\"entities\":{" + entities + "}}");
            }
        };

        List<String> fifty = new ArrayList<>();
        for (int i = 1; i <= 50; i++) fifty.add("Q" + i);

        WikidataApiClient.PartialEntities result =
                client.getEntityClaimsPartial(fifty, List.of("P31"), null);

        assertEquals(50, result.entities().size());
        assertEquals(0, result.failedBatches(),
                "a batch that only needed splitting must not fail the run");
        assertTrue(attempted.contains(50) && attempted.stream().anyMatch(n -> n <= 12),
                "the 50 was split until the server could answer: " + attempted);
    }
}
