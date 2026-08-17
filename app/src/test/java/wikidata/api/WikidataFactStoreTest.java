package wikidata.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * A hit rate near zero has two very different causes, and the run cannot act on it
     * without knowing which: consumers that never ask about the same entity (a larger
     * budget buys nothing) or consumers that do, but only after the document was
     * dropped (a larger budget buys every one of those fetches back).
     */
    @Test void anEvictedDocumentAskedForAgainIsCountedAsSuch() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WikidataFactStore store = new WikidataFactStore(700);
        store.accept(mapper.readTree("""
                {"entities":{"Q1":{"id":"Q1","labels":{"en":{"value":"one"}}}}}
                """), false);
        store.accept(mapper.readTree("""
                {"entities":{"Q2":{"id":"Q2","labels":{"en":{"value":"two"}}}}}
                """), false);

        assertTrue(store.evictions() > 0, "the budget forced a document out");
        assertEquals(0, store.evictedRefetches(), "nobody has asked for it yet");

        store.missing(List.of("Q1"), false);
        assertEquals(1, store.evictedRefetches(),
                "asking for an evicted document is a fetch a bigger budget would save");

        store.missing(List.of("Q9"), false);
        assertEquals(1, store.evictedRefetches(),
                "a document the store never held is not an eviction cost");
    }

    /**
     * The whole claims body of an entity is what a run cannot afford to hold — measured
     * on this project's own pattern, the working set is ~18k documents at ~260 KB each.
     * Retaining only the properties a fetch was made for is what brings the working set
     * within a budget that can actually be held.
     */
    @Test void onlyThePropertiesTheFetchWasForAreRetained() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WikidataFactStore store = new WikidataFactStore();
        JsonNode fetched = mapper.readTree("""
                {"entities":{"Q42":{"id":"Q42","labels":{"en":{"value":"Adams"}},
                  "claims":{"P31":[{"x":1}],"P569":[{"x":2}],"P1080":[{"x":3}]}}}}
                """);

        store.accept(fetched, true, List.of("P31"));

        JsonNode held = store.response(List.of("Q42"), true, List.of("P31"), mapper)
                .path("entities").path("Q42");
        assertTrue(held.path("claims").has("P31"), "the property asked for is kept");
        assertFalse(held.path("claims").has("P1080"), "the rest of the body is not");
        assertTrue(held.path("labels").has("en"), "labels cost little and everyone wants them");
    }

    /**
     * A slice that kept P31 knows nothing about P569 — and must SAY so. Answering from
     * it would report "this entity has no date of birth" when the truth is that nobody
     * ever fetched one: a cache turning a miss into false data.
     */
    @Test void aSliceDoesNotAnswerForAPropertyItNeverKept() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WikidataFactStore store = new WikidataFactStore();
        store.accept(mapper.readTree("""
                {"entities":{"Q42":{"id":"Q42","claims":{"P31":[{"x":1}]}}}}
                """), true, List.of("P31"));

        assertEquals(List.of(), store.missing(List.of("Q42"), true, List.of("P31")));
        assertEquals(List.of("Q42"), store.missing(List.of("Q42"), true, List.of("P569")),
                "a property never retained is a miss, not an absence");
        assertNull(store.response(List.of("Q42"), true, List.of("P569"), mapper));

        // Fetching that property merges into the same document rather than replacing it.
        store.accept(mapper.readTree("""
                {"entities":{"Q42":{"id":"Q42","claims":{"P569":[{"x":2}]}}}}
                """), true, List.of("P569"));

        assertEquals(List.of(), store.missing(List.of("Q42"), true, List.of("P31", "P569")),
                "one document now answers both declarations");
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
