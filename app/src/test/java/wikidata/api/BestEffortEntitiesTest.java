package wikidata.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A best-effort resolve keeps what the successful batches returned.
 *
 * <p>Label resolution is optional work: an unresolved reference keeps its QID as its
 * name, which is a degraded label rather than a wrong answer. Failing the whole call
 * for one throttled batch threw away every label the others had already returned —
 * and downstream, a reference with no label was being read as a deleted entity.
 *
 * <p>The isolation belongs HERE and not in the caller: batching at the call site to
 * catch each batch's failure would hand the client one batch at a time and serialise
 * a pass that otherwise fans out.
 */
class BestEffortEntitiesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A client whose batch containing a given QID fails, recording every batch it was
     *  asked for so the fan-out can be checked. */
    private static class BatchFailingClient extends WikidataApiClient {
        private final Set<String> failFor;
        final List<List<String>> requested = new ArrayList<>();

        BatchFailingClient(Set<String> failFor) {
            super("test");
            this.failFor = failFor;
        }

        @Override
        protected JsonNode getEntitiesBatchWithRetry(List<String> qids, boolean withClaims, List<String> pids)
                throws Exception {
            synchronized (requested) {
                requested.add(List.copyOf(qids));
            }
            if (qids.stream().anyMatch(failFor::contains)) {
                throw new java.io.IOException("HTTP 429");
            }
            StringBuilder json = new StringBuilder("{\"entities\":{");
            for (int i = 0; i < qids.size(); i++) {
                if (i > 0) json.append(',');
                json.append('"').append(qids.get(i)).append("\":{\"id\":\"")
                    .append(qids.get(i)).append("\",\"labels\":{\"en\":{\"value\":\"Label ")
                    .append(qids.get(i)).append("\"}}}");
            }
            return MAPPER.readTree(json.append("}}").toString());
        }
    }

    private static List<String> qids(int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i <= to; i++) out.add("Q" + i);
        return out;
    }

    @Test void oneFailedBatchCostsOnlyItsOwnLabels() throws Exception {
        // 150 refs = three 50-QID batches; the middle one is refused.
        BatchFailingClient client = new BatchFailingClient(Set.of("Q51"));
        WikidataApiClient.PartialEntities resolved =
                client.getEntitiesBestEffort(qids(1, 150), List.of(), null);

        assertEquals(1, resolved.failedBatches());
        assertNotNull(resolved.entities().get("Q1"), "batch 1 survived");
        assertNotNull(resolved.entities().get("Q150"), "batch 3 survived");
        assertNull(resolved.entities().get("Q51"), "only the refused batch is missing");
        assertEquals(100, resolved.entities().size(),
                     "two batches' worth of labels, not zero");
    }

    /** The strict form is what claims use, and it must stay strict: a missing claim
     *  is indistinguishable from an absent one, so a throttled batch would otherwise
     *  become "this entity has no P840". */
    @Test void theStrictFormStillFailsTheWholeCall() throws Exception {
        BatchFailingClient client = new BatchFailingClient(Set.of("Q51"));

        assertThrows(Exception.class,
                     () -> client.getEntities(qids(1, 150), List.of("P840"), null));
    }

    /**
     * The reason the isolation lives in the client: ONE call still yields all the
     * physical batches, so they fan out over the client's pool. Isolating at the call
     * site meant handing the client one batch per call, which serialises the pass —
     * the deterministic signature of that regression is exactly this count.
     */
    @Test void oneCallStillProducesEveryBatchSoTheyCanFanOut() throws Exception {
        BatchFailingClient client = new BatchFailingClient(Set.of());

        client.getEntitiesBestEffort(qids(1, 300), List.of(), null);

        assertEquals(6, client.requested.size(),
                     "300 refs is six batches of 50, from a single call");
    }
}
