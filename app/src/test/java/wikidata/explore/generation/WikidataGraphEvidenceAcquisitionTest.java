package wikidata.explore.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import datasource.EntityRef;
import datasource.LiteralValue;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.GraphEvidenceCondition;
import datasource.graph.constraint.GraphEvidenceConditionResult;
import datasource.graph.constraint.GraphPath;
import datasource.graph.constraint.GraphRelationExists;
import datasource.graph.constraint.GraphRelationReaches;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.InMemoryGraphStore;
import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The historical-position condition is acquired as two bounded fact-store-backed
 * phases; a missing response stays Review and a literal P576 remains a real witness.
 */
class WikidataGraphEvidenceAcquisitionTest {
    private static final GraphTraversalDirection OUT = GraphTraversalDirection.OUTGOING;
    private static final GraphRelation JURISDICTION = relation("P1001");
    private static final GraphRelation COUNTRY = relation("P17");
    private static final GraphRelation DIRECTS = relation("P2389");
    private static final GraphRelation DISSOLVED = relation("P576");
    private static final GraphRelation KIND = relation("P31");
    private static final EntityRef HISTORICAL_POLITY = entity("Q3024240");

    @Test void historicalPositionsAreClassifiedFromTwoCachedAcquisitionPhases()
            throws Exception {
        FixtureClient api = fixtureClient();
        List<EntityRef> positions = List.of(
                entity("Q6412254"), entity("Q9001"), entity("Q9002"), entity("Q9004"));

        var first = WikidataGraphEvidenceAcquisition.acquire(
                api, new InMemoryGraphStore(), condition(), positions, null);

        assertEquals(1, first.accepted());
        assertEquals(2, first.rejected());
        assertEquals(1, first.review());
        GraphEvidenceConditionResult apostolic = first.classifications().getFirst();
        assertEquals(2, apostolic.witnesses().size(),
                "jurisdiction and country both carry the accepted verdict");
        assertTrue(apostolic.witnesses().stream().allMatch(witness ->
                witness.testEdge().target() instanceof LiteralValue));
        assertEquals("Q171150$P576", apostolic.witnesses().getFirst()
                .testEdge().provenanceId());
        assertInstanceOf(LiteralValue.class,
                apostolic.witnesses().getFirst().testEdge().target());
        assertEquals(GraphEvidenceConditionResult.Decision.REJECTED,
                first.classifications().get(3).decision(),
                "literal text shaped like a QID is not an entity reached by P31");
        assertEquals(2, api.physicalRequests.get(),
                "one population batch and one reached-polity batch");

        var second = WikidataGraphEvidenceAcquisition.acquire(
                api, new InMemoryGraphStore(), condition(), positions, null);

        assertEquals(1, second.accepted());
        assertEquals(2, second.rejected());
        assertEquals(1, second.review());
        assertEquals(2, api.physicalRequests.get(),
                "a fresh local graph is rebuilt from the shared raw fact cache");
        assertTrue(api.facts().cacheHits() >= 5);
    }

    @Test void aFailedSecondHopCanOnlyProduceReview() throws Exception {
        WikidataApiClient api = new WikidataApiClient("test") {
            @Override public PartialStatements getStatementsByPropertyPartial(
                    List<String> qids, List<String> pids, BatchLog log) {
                if (pids.contains("P1001")) {
                    return new PartialStatements(Map.of(
                            "P1001", Map.of("Q6412254", List.of(
                                    new ApiStatement("Q6412254$P1001", "Q171150",
                                            Map.of())))), 0, List.of());
                }
                return new PartialStatements(Map.of(), 1, List.of("Q171150"));
            }
        };
        InMemoryGraphStore store = new InMemoryGraphStore();

        var result = WikidataGraphEvidenceAcquisition.acquire(
                api, store, condition(), List.of(entity("Q6412254")), null);

        assertEquals(0, result.accepted());
        assertEquals(0, result.rejected());
        assertEquals(1, result.review());
        assertEquals(1, result.testFailedBatches());
        assertEquals(GraphAdjacencyCoverage.UNAVAILABLE,
                store.adjacencyKnowledge(entity("Q171150"), new GraphAdjacencyDemand(
                        List.of(entity("Q171150")), DISSOLVED, OUT)));
    }

    @Test void populationAcquisitionUsesTheExistingFiftyEntityBatchBoundary()
            throws Exception {
        FixtureClient api = new FixtureClient(Map.of());
        List<EntityRef> positions = new ArrayList<>();
        for (int i = 1; i <= 51; i++) positions.add(entity("Q" + (10000 + i)));

        var result = WikidataGraphEvidenceAcquisition.acquire(
                api, new InMemoryGraphStore(), condition(), positions, null);

        assertEquals(51, result.review());
        assertEquals(2, api.physicalRequests.get());
    }

    @Test void unsupportedIncomingAcquisitionIsExplicit() {
        GraphEvidenceCondition incoming = new GraphEvidenceCondition(
                "incoming evidence",
                List.of(GraphPath.direct(JURISDICTION,
                        GraphTraversalDirection.INCOMING)),
                List.of(new GraphRelationExists(DISSOLVED, OUT)), null);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> WikidataGraphEvidenceAcquisition.acquire(
                        fixtureClient(), new InMemoryGraphStore(), incoming,
                        List.of(entity("Q6412254")), null));

        assertTrue(failure.getMessage().contains("outgoing relations only"));
    }

    @Test void theWholeProviderPlanIsValidatedBeforeAcquisitionStarts() {
        FixtureClient api = fixtureClient();
        GraphEvidenceCondition incomingTest = new GraphEvidenceCondition(
                "incoming test",
                List.of(GraphPath.direct(JURISDICTION, OUT)),
                List.of(new GraphRelationExists(
                        DISSOLVED, GraphTraversalDirection.INCOMING)), null);

        assertThrows(IllegalArgumentException.class,
                () -> WikidataGraphEvidenceAcquisition.acquire(
                        api, new InMemoryGraphStore(), incomingTest,
                        List.of(entity("Q6412254")), null));

        assertEquals(0, api.physicalRequests.get(),
                "an invalid second hop must not spend or retain the first hop");
    }

    private static GraphEvidenceCondition condition() {
        return new GraphEvidenceCondition(
                "historical polity",
                List.of(GraphPath.direct(JURISDICTION, OUT),
                        GraphPath.direct(COUNTRY, OUT),
                        GraphPath.direct(DIRECTS, OUT)),
                List.of(new GraphRelationExists(DISSOLVED, OUT),
                        new GraphRelationReaches(KIND, OUT, HISTORICAL_POLITY)),
                null);
    }

    private static FixtureClient fixtureClient() {
        return new FixtureClient(Map.of(
                "Q6412254", entityJson("Q6412254", Map.of(
                        "P1001", qidStatement("Q6412254$P1001", "Q171150"),
                        "P17", qidStatement("Q6412254$P17", "Q171150"))),
                "Q9001", entityJson("Q9001", Map.of(
                        "P1001", qidStatement("Q9001$P1001", "Q9003"))),
                "Q9002", entityJson("Q9002", Map.of()),
                "Q9004", entityJson("Q9004", Map.of(
                        "P1001", qidStatement("Q9004$P1001", "Q9005"))),
                "Q171150", entityJson("Q171150", Map.of(
                        "P576", timeStatement("Q171150$P576",
                                "+1946-02-01T00:00:00Z"))),
                "Q9003", entityJson("Q9003", Map.of(
                        "P31", statements(
                                qidStatement("Q9003$normal", "Q3024240"),
                                rankedQidStatement("Q9003$preferred", "Q6256",
                                        "preferred")))),
                "Q9005", entityJson("Q9005", Map.of(
                        "P31", stringStatement("Q9005$P31", "Q3024240")))));
    }

    private static final class FixtureClient extends WikidataApiClient {
        private final ObjectMapper mapper = new ObjectMapper();
        private final Map<String, JsonNode> entities;
        private final AtomicInteger physicalRequests = new AtomicInteger();

        private FixtureClient(Map<String, JsonNode> entities) {
            super("test");
            this.entities = new LinkedHashMap<>(entities);
        }

        @Override protected JsonNode getEntitiesBatch(
                List<String> qids, boolean withClaims) {
            physicalRequests.incrementAndGet();
            ObjectNode root = mapper.createObjectNode();
            ObjectNode answer = root.putObject("entities");
            for (String qid : qids) {
                answer.set(qid, entities.getOrDefault(
                        qid, entityJson(qid, Map.of())).deepCopy());
            }
            return root;
        }
    }

    private static JsonNode entityJson(String qid, Map<String, JsonNode> claims) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode entity = mapper.createObjectNode();
        entity.put("id", qid);
        ObjectNode grouped = entity.putObject("claims");
        claims.forEach((pid, statements) -> {
            var values = grouped.putArray(pid);
            if (statements.isArray()) values.addAll((com.fasterxml.jackson.databind.node.ArrayNode)
                    statements);
            else values.add(statements);
        });
        return entity;
    }

    private static JsonNode qidStatement(String guid, String qid) {
        return rankedQidStatement(guid, qid, "normal");
    }

    private static JsonNode rankedQidStatement(String guid, String qid, String rank) {
        ObjectNode statement = statement(guid);
        statement.put("rank", rank);
        statement.withObject("mainsnak").withObject("datavalue")
                .put("type", "wikibase-entityid")
                .putObject("value").put("id", qid);
        return statement;
    }

    private static JsonNode stringStatement(String guid, String value) {
        ObjectNode statement = statement(guid);
        statement.withObject("mainsnak").withObject("datavalue")
                .put("type", "string").put("value", value);
        return statement;
    }

    private static JsonNode statements(JsonNode... statements) {
        var values = new ObjectMapper().createArrayNode();
        values.addAll(List.of(statements));
        return values;
    }

    private static JsonNode timeStatement(String guid, String time) {
        ObjectNode statement = statement(guid);
        ObjectNode value = statement.withObject("mainsnak")
                .withObject("datavalue").put("type", "time").putObject("value");
        value.put("time", time);
        value.put("precision", 11);
        value.put("calendarmodel", "http://www.wikidata.org/entity/Q1985727");
        return statement;
    }

    private static ObjectNode statement(String guid) {
        ObjectNode statement = new ObjectMapper().createObjectNode();
        statement.put("id", guid);
        statement.put("rank", "normal");
        statement.putObject("mainsnak").put("snaktype", "value");
        return statement;
    }

    private static GraphRelation relation(String pid) {
        return new GraphRelation("wikidata", pid);
    }

    private static EntityRef entity(String qid) {
        return EntityRef.wikidata(qid);
    }
}
