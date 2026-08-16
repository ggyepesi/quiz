package wikidata.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikidataFactStoreTest {
    @Test void oneClaimsDocumentServesEntityAndStatementConsumers() throws Exception {
        class RecordingClient extends WikidataApiClient {
            int physical;
            RecordingClient() { super(DEFAULT_USER_AGENT); }
            @Override protected JsonNode getEntitiesBatch(List<String> qids,
                                                          boolean withClaims) throws Exception {
                physical++;
                return new ObjectMapper().readTree("""
                        {"entities":{"Q1":{"id":"Q1","labels":{"en":{"value":"One"}},
                        "claims":{"P31":[{"id":"Q1$s1","rank":"normal","mainsnak":
                        {"datavalue":{"type":"wikibase-entityid","value":{"id":"Q5"}}}}]}}}}
                        """);
            }
        }
        RecordingClient client = new RecordingClient();

        assertEquals(List.of("Q5"),
                client.getEntities(List.of("Q1"), List.of("P31")).get("Q1").claim("P31"));
        assertEquals("Q5", client.getStatements(
                List.of("Q1"), "P31", List.of(), null).get("Q1").getFirst().value());
        assertEquals(1, client.physical);
        assertEquals(1, client.facts().cacheHits());
    }

    @Test void weightedLruEvictsOldDocumentsAndInspectionDoesNotCountHits()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WikidataFactStore store = new WikidataFactStore(700);
        store.accept(mapper.readTree("""
                {"entities":{"Q1":{"id":"Q1","labels":{"en":{"value":"one"}}}}}
                """), false);
        store.missing(List.of("Q1"), false);
        store.missing(List.of("Q1"), false);
        assertEquals(0, store.cacheHits(), "inspection is metric-free");

        store.accept(mapper.readTree("""
                {"entities":{"Q2":{"id":"Q2","labels":{"en":{"value":"two"}}},
                             "Q3":{"id":"Q3","labels":{"en":{"value":"three"}}}}}
                """), false);

        assertTrue(store.estimatedBytes() <= 700);
        assertNull(store.response(List.of("Q1"), false, mapper),
                "the least-recently-used document was evicted");
    }
}
