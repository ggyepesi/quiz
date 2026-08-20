package wikidata.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikidataFactStoreTest {
    @Test void measurementIsBoundedAndReportsTruncation() {
        WikidataFactStore store = new WikidataFactStore(1_536);
        store.recordDemand("test", List.of("Q1", "Q2"), List.of("P31"));

        assertTrue(store.measurementTruncated());
        assertTrue(store.measurementEstimatedBytes() <= 1_536 / 8,
                "measurement detail observes its reserved share of the cache budget");
        assertTrue(store.estimatedBytes() <= 1_536);
    }

    /**
     * Under pressure the budget belongs to the facts somebody intends to re-read. A
     * statement body is fetched, parsed once and never asked for again; holding it in
     * arrival order evicted the properties every later pass DID read, and one real run
     * fetched 3,018 documents a second time for that reason. Retention itself is
     * unchanged — one claims document still answers every consumer that asks — only the
     * order in which the budget gives way.
     */
    @Test void anUnplannedSliceYieldsTheBudgetBeforeAPlannedOne() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // Size the budget from a real document rather than a guess: room for two.
        WikidataFactStore probe = new WikidataFactStore(Long.MAX_VALUE / 4);
        probe.accept(entity(mapper, "Q1", "P1411"), true, List.of("P1411"));
        long one = probe.estimatedBytes();

        WikidataFactStore store = new WikidataFactStore(one * 2 + 1_024);
        store.recordRetentionPlan("Nominee", List.of("Q2", "Q3"), List.of("P31"));

        // The planned slice arrives FIRST, the unplanned one after it: by age alone the
        // planned document is the one that would go.
        store.accept(entity(mapper, "Q2", "P31"), true, List.of("P31"));
        store.accept(entity(mapper, "Q1", "P1411"), true, null);
        assertEquals(2, store.size());

        store.accept(entity(mapper, "Q3", "P31"), true, List.of("P31"));

        assertEquals(1, store.unplannedEvictions());
        assertEquals(List.of("Q1"), store.missing(List.of("Q1"), true, null),
                "an unplanned whole body yields despite being able to answer anything");
        assertTrue(store.missing(List.of("Q2"), true, List.of("P31")).isEmpty(),
                "the older, planned slice a later pass asks for is still held");
        assertTrue(store.missing(List.of("Q3"), true, List.of("P31")).isEmpty());
    }

    /**
     * Past the measurement cap a QID/PID pair simply has no record, and "no record" is
     * not "nobody wants it". Reading absence as unplanned inverted the eviction order
     * exactly where it matters most — a domain big enough to exhaust the measurement is
     * a domain whose budget is under pressure — so the entity's own plan, one entry per
     * entity rather than per pair, is what truncation falls back to.
     */
    @Test void aTruncatedMeasurementMakesPriorityCoarserNotBackwards() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WikidataFactStore probe = new WikidataFactStore(Long.MAX_VALUE / 4);
        probe.accept(entity(mapper, "Q1", "P31"), true, List.of("P31"));
        long one = probe.estimatedBytes();

        // A budget whose measurement share (an eighth) holds fewer pairs than the run
        // plans, so the plan is recorded past the cap.
        WikidataFactStore store = new WikidataFactStore(one * 2 + 1_024);
        for (int i = 0; i < 100; i++) {
            store.recordRetentionPlan("filler", List.of("QF" + i), List.of("P999"));
        }
        store.recordRetentionPlan("Person", List.of("Q2", "Q3"), List.of("P31"));
        assertTrue(store.measurementTruncated(), "the cap is genuinely exhausted here");

        store.accept(entity(mapper, "Q2", "P31"), true, List.of("P31"));
        store.accept(entity(mapper, "Q1", "P31"), true, List.of("P31"));
        store.accept(entity(mapper, "Q3", "P31"), true, List.of("P31"));

        assertEquals(List.of("Q1"), store.missing(List.of("Q1"), true, List.of("P31")),
                "the entity nobody planned still yields first");
        assertTrue(store.missing(List.of("Q2"), true, List.of("P31")).isEmpty(),
                "a planned entity is not evicted merely because its pair went unmeasured");
    }

    @Test void samePropertyDoesNotMakeAnUnrelatedEntityPlanned() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WikidataFactStore probe = new WikidataFactStore(Long.MAX_VALUE / 4);
        probe.accept(entity(mapper, "Q1", "P31"), true, List.of("P31"));
        long one = probe.estimatedBytes();
        WikidataFactStore store = new WikidataFactStore(one * 2 + 1_024);
        store.recordRetentionPlan("Person", List.of("Q2", "Q3"), List.of("P31"));
        store.accept(entity(mapper, "Q2", "P31"), true, List.of("P31"));
        store.accept(entity(mapper, "Q1", "P31"), true, List.of("P31"));

        store.accept(entity(mapper, "Q3", "P31"), true, List.of("P31"));

        assertEquals(List.of("Q1"),
                store.missing(List.of("Q1"), true, List.of("P31")));
        assertTrue(store.missing(List.of("Q2", "Q3"), true, List.of("P31")).isEmpty());
    }

    @Test void batchAnswerKeepsCachedHalfWhenAcceptingFetchedHalfEvictsIt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WikidataFactStore probe = new WikidataFactStore(Long.MAX_VALUE / 4);
        probe.accept(entity(mapper, "Q1", "P31"), true, List.of("P31"));
        long one = probe.estimatedBytes();
        WikidataFactStore store = new WikidataFactStore(one + 32);
        store.accept(entity(mapper, "Q1", "P31"), true, List.of("P31"));

        class Client extends WikidataApiClient {
            Client() { super("test"); facts(store); }
            @Override protected JsonNode getEntitiesBatch(List<String> qids, boolean claims) {
                try {
                    return entity(mapper, "Q2", "P31");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            JsonNode batch() throws Exception {
                return getEntitiesBatchWithRetry(
                        List.of("Q1", "Q2"), true, List.of("P31"));
            }
        }

        JsonNode result = new Client().batch();

        assertTrue(result.path("entities").has("Q1"),
                "the entity answered from cache must survive in the returned batch");
        assertTrue(result.path("entities").has("Q2"));
        assertEquals(1, store.size(), "accepting Q2 really did force one cache eviction");
    }

    private static JsonNode entity(ObjectMapper mapper, String qid, String pid)
            throws Exception {
        return mapper.readTree("{\"entities\":{\"" + qid + "\":{\"id\":\"" + qid
                + "\",\"labels\":{\"en\":{\"value\":\"" + qid + " padded label value"
                + " long enough to weigh something\"}},\"claims\":{\"" + pid
                + "\":[{\"id\":\"" + qid + "$s\",\"rank\":\"normal\",\"mainsnak\":"
                + "{\"datavalue\":{\"type\":\"wikibase-entityid\",\"value\":"
                + "{\"id\":\"Q7\"}}}}]}}}}");
    }

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
                client.getEntities(List.of("Q1"), List.of("P31")).get("Q1")
                        .entityQids("P31"));
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

    @Test void anEvictedPropertySliceDoesNotPolluteAnotherPropertyMetric()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WikidataFactStore probe = new WikidataFactStore(Long.MAX_VALUE / 4);
        probe.accept(entity(mapper, "Q1", "P1411"), true, List.of("P1411"));
        long one = probe.estimatedBytes();
        WikidataFactStore store = new WikidataFactStore(one + 32);
        store.accept(entity(mapper, "Q1", "P1411"), true, List.of("P1411"));
        store.accept(entity(mapper, "Q2", "P31"), true, List.of("P31"));

        store.missing(List.of("Q1"), true, List.of("P136"));
        assertEquals(0, store.evictedRefetches(),
                "P136 was never retained, so this is its first fetch, not a re-fetch");

        store.missing(List.of("Q1"), true, List.of("P1411"));
        assertEquals(1, store.evictedRefetches(),
                "the exact evicted P1411 capability is a genuine re-fetch");
    }

    @Test void anEvictedWholeClaimsBodyCanAnswerAPropertyRequest() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WikidataFactStore probe = new WikidataFactStore(Long.MAX_VALUE / 4);
        probe.accept(entity(mapper, "Q1", "P1411"), true, null);
        long one = probe.estimatedBytes();
        WikidataFactStore store = new WikidataFactStore(one + 32);
        store.accept(entity(mapper, "Q1", "P1411"), true, null);
        store.accept(entity(mapper, "Q2", "P31"), true, List.of("P31"));

        store.missing(List.of("Q1"), true, List.of("P136"));

        assertEquals(1, store.evictedRefetches(),
                "a whole claims body really could have answered later P136 demand");
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

    @Test void propertyUsageSeparatesBankedFactsFromRealConsumerDemand()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WikidataFactStore store = new WikidataFactStore();
        store.recordRetentionPlan(
                "Nominee", List.of("Q42"), List.of("P31", "P569"));
        store.accept(mapper.readTree("""
                {"entities":{"Q42":{"id":"Q42","claims":{
                  "P31":[{"x":1}],"P569":[{"x":2}]}}}}
                """), true, List.of("P31", "P569"));
        store.recordDemand("Nominee", List.of("Q42"), List.of("P31"));
        store.recordHits(1, List.of("P31"));

        Map<String, WikidataFactStore.PropertyUsage> usage = store.propertyUsage().stream()
                .collect(java.util.stream.Collectors.toMap(
                        WikidataFactStore.PropertyUsage::propertyPid, p -> p));
        assertEquals(1, usage.get("P31").demandedEntities());
        assertEquals(0, usage.get("P31").unusedEntities());
        assertEquals(1, usage.get("P31").cacheHits());
        assertEquals(0, usage.get("P31").lateDemands(),
                "a retained-before-fetch fact is planned even when consumed later");
        assertEquals(0, usage.get("P569").demandedEntities());
        assertEquals(1, usage.get("P569").unusedEntities(),
                "a prospective property is visible until a later consumer needs it");
        assertTrue(usage.get("P569").unusedEstimatedBytes() > 0);
        assertEquals(1L, store.demandsBySource().get("Nominee").get("P31"));
        Map<String, WikidataFactStore.RetentionUsage> retention =
                store.retentionUsage().stream().collect(java.util.stream.Collectors.toMap(
                        WikidataFactStore.RetentionUsage::propertyPid, p -> p));
        assertEquals("Nominee", retention.get("P569").source());
        assertTrue(retention.get("P569").unusedEstimatedBytes() > 0);
        assertEquals(1, store.preplannedDemandPairs());
        assertEquals(0, store.lateDemandPairs());
    }

    @Test void demandAfterAnUnplannedFirstFetchIsReportedAsLate() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WikidataFactStore store = new WikidataFactStore();
        store.accept(mapper.readTree("""
                {"entities":{"Q42":{"id":"Q42","claims":{"P31":[]}}}}
                """), true, List.of("P31"));

        store.recordDemand("late consumer", List.of("Q42"), List.of("P31"));

        assertEquals(1, store.lateDemandPairs());
        assertEquals(0, store.preplannedDemandPairs());
        assertEquals(1, store.propertyUsage().getFirst().lateDemands());
    }

    @Test void aLaterSliceDoesNotDowngradeACompleteClaimsDocument() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WikidataFactStore store = new WikidataFactStore();
        store.accept(mapper.readTree("""
                {"entities":{"Q42":{"id":"Q42","claims":{
                  "P31":[{"x":1}],"P569":[{"x":2}]}}}}
                """), true);

        store.accept(mapper.readTree("""
                {"entities":{"Q42":{"id":"Q42","claims":{"P31":[{"x":1}]}}}}
                """), true, List.of("P31"));

        JsonNode held = store.response(List.of("Q42"), true, List.of("P569"), mapper);
        assertTrue(held.path("entities").path("Q42").path("claims").has("P569"),
                "a later slice cannot replace a complete claims document");
    }

    @Test void anOversizedReplacementDoesNotDiscardTheUsefulPreviousSlice()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WikidataFactStore store = new WikidataFactStore(700);
        store.accept(mapper.readTree("""
                {"entities":{"Q42":{"id":"Q42","claims":{"P31":[]}}}}
                """), true, List.of("P31"));
        assertEquals(List.of(), store.missing(List.of("Q42"), true, List.of("P31")));

        store.accept(mapper.readTree("""
                {"entities":{"Q42":{"id":"Q42","claims":{"P569":[{
                  "value":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                },{
                  "value":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                },{
                  "value":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                }]}}}}
                """), true, List.of("P569"));

        assertTrue(store.oversized() > 0, "the augmentation exceeded the test budget");
        assertEquals(List.of(), store.missing(List.of("Q42"), true, List.of("P31")),
                "the smaller retained slice remains useful");
        assertEquals(List.of("Q42"), store.missing(
                List.of("Q42"), true, List.of("P569")),
                "the oversized augmentation itself was not retained");
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
