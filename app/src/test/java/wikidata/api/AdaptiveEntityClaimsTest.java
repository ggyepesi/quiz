package wikidata.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveEntityClaimsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class SplittingClient extends WikidataApiClient {
        private final List<Integer> attemptedSizes =
                java.util.Collections.synchronizedList(new ArrayList<>());
        private final String permanentlyUnavailable;

        private SplittingClient(String permanentlyUnavailable) {
            super("test");
            this.permanentlyUnavailable = permanentlyUnavailable;
        }

        @Override protected JsonNode getEntitiesBatchWithRetry(
                List<String> qids, boolean withClaims) throws Exception {
            attemptedSizes.add(qids.size());
            if (qids.size() > 3 || (permanentlyUnavailable != null
                    && qids.contains(permanentlyUnavailable))) {
                throw new HttpTimeoutException("deliberate timeout");
            }
            StringBuilder entities = new StringBuilder();
            for (String qid : qids) {
                if (!entities.isEmpty()) entities.append(',');
                entities.append('"').append(qid).append("\":{\"id\":\"")
                        .append(qid).append("\",\"labels\":{},\"claims\":{}}");
            }
            return JSON.readTree("{\"entities\":{" + entities + "}}");
        }
    }

    @Test void timeoutSplitsUntilEveryEntityCanBeFetched() throws Exception {
        SplittingClient client = new SplittingClient(null);

        WikidataApiClient.PartialEntities result = client.getEntityClaimsPartial(
                qids(10), List.of("P31"), null);

        assertEquals(10, result.entities().size());
        assertEquals(0, result.failedBatches());
        assertTrue(client.attemptedSizes.containsAll(List.of(10, 5, 2)));
    }

    @Test void exhaustedSingletonIsReportedWhileItsSiblingsSurvive() throws Exception {
        SplittingClient client = new SplittingClient("Q7");

        WikidataApiClient.PartialEntities result = client.getEntityClaimsPartial(
                qids(10), List.of("P31"), null);

        assertEquals(9, result.entities().size());
        assertTrue(!result.entities().containsKey("Q7"));
        assertEquals(1, result.failedBatches());
        assertEquals(List.of("Q7"), result.unavailableQids());
    }

    @Test void requiredStatementLoadAlsoSplitsLargeTimedOutRequests() throws Exception {
        SplittingClient client = new SplittingClient(null);

        client.getStatements(qids(10), "P1411", List.of("P805"), null);

        assertTrue(client.attemptedSizes.containsAll(List.of(10, 5, 2)));
    }

    @Test void requiredStatementLoadCannotSilentlyLoseAnExhaustedQid() {
        SplittingClient client = new SplittingClient("Q7");

        assertThrows(Exception.class, () -> client.getStatements(
                qids(10), "P1411", List.of("P805"), null));
    }

    private static List<String> qids(int count) {
        List<String> values = new ArrayList<>();
        for (int i = 1; i <= count; i++) values.add("Q" + i);
        return values;
    }
}
