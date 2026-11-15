package wikidata.explore.generation;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.InMemoryGraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.FakeWikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.api.FakeWikidataApiClient;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Incoming configured edges use the same graph-store demand shape as local waves. */
class WikidataGraphAdjacencyAcquisitionTest {
    @TempDir Path cacheDirectory;

    @Test void aGraphAcquisitionNamesItsInitialRequestTotal() {
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                java.util.stream.IntStream.range(0, 101)
                        .mapToObj(i -> EntityRef.wikidata("Q" + (i + 1))).toList(),
                new GraphRelation("wikidata", "P1001"),
                GraphTraversalDirection.OUTGOING);

        assertEquals("Acquire P1001 out — 101 node(s), 3 initial request(s); "
                        + "adaptive splits may add requests",
                WikidataGraphAdjacencyAcquisition.acquisitionTitle(demand));
    }

    @Test void anUnavailableOutgoingEntityCannotBecomeComplete() throws Exception {
        EntityRef position = EntityRef.wikidata("Q4164871");
        GraphRelation jurisdiction = new GraphRelation("wikidata", "P1001");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(position),
                jurisdiction, GraphTraversalDirection.OUTGOING);
        WikidataApiClient api = new WikidataApiClient("test") {
            @Override public PartialStatements getStatementsByPropertyPartial(
                    List<String> qids, List<String> pids, BatchLog log,
                    StatementBatchCommitter committer) {
                return new PartialStatements(Map.of(), 1, List.of("Q4164871"));
            }
        };
        InMemoryGraphStore store = new InMemoryGraphStore();

        new WikidataGraphAdjacencyAcquisition(api, null, null, null)
                .acquire(store, demand);

        assertEquals(GraphAdjacencyCoverage.UNAVAILABLE,
                store.adjacencyKnowledge(position, demand));
    }

    @Test void aStatementAndItsQualifierRemainSeparateTraversableEdges()
            throws Exception {
        EntityRef laocoon = EntityRef.wikidata("Q101");
        GraphRelation statementRelation =
                new GraphRelation("wikidata", "statement:P40");
        GraphRelation roleRelation =
                new GraphRelation("wikidata", "qualifier:P3831");
        List<String> statementRequests = new ArrayList<>();
        FakeWikidataApiClient api = new FakeWikidataApiClient() {
            @Override public Map<String, List<ApiStatement>> getStatements(
                    List<String> entityQids, String statementPid,
                    List<String> qualifierPids, BatchLog batchLog,
                    StatementDetail detail) {
                statementRequests.add(statementPid + " " + entityQids);
                return super.getStatements(entityQids, statementPid, qualifierPids,
                        batchLog, detail);
            }
        }.statement("Q101", "P40", "Q101$P40-1", "Q201",
                Map.of("P3831", List.of("Q301")));
        InMemoryGraphStore store = new InMemoryGraphStore();
        WikidataGraphAdjacencyAcquisition acquisition =
                new WikidataGraphAdjacencyAcquisition(api, null, null, null);

        GraphAdjacencyDemand statements = new GraphAdjacencyDemand(List.of(laocoon),
                statementRelation, GraphTraversalDirection.OUTGOING);
        acquisition.acquire(store, statements);
        EntityRef statement = (EntityRef) store.adjacent(statements).edges().get(0).target();
        GraphAdjacencyDemand roles = new GraphAdjacencyDemand(List.of(statement),
                roleRelation, GraphTraversalDirection.OUTGOING);
        acquisition.acquire(store, roles);

        assertEquals("wikidata-statement", statement.namespace());
        assertEquals("Q101$P40-1", store.adjacent(statements).edges().get(0)
                .provenanceId());
        assertEquals(List.of(EntityRef.wikidata("Q301")), store.adjacent(roles).edges()
                .stream().map(edge -> (EntityRef) edge.target()).toList());
        assertEquals("Q101$P40-1", store.adjacent(roles).edges().get(0).provenanceId());
        assertEquals(List.of("P40 [Q101]"), statementRequests,
                "the qualifier is read from the statement response, not requested again");
    }

    @Test void everyCompletedOutgoingBatchIsUsableAfterRestart() throws Exception {
        EntityRef position = EntityRef.wikidata("Q4164871");
        GraphRelation jurisdiction = new GraphRelation("wikidata", "P1001");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(position),
                jurisdiction, GraphTraversalDirection.OUTGOING);
        WikidataApiClient api = new WikidataApiClient("test") {
            @Override public PartialStatements getStatementsByPropertyPartial(
                    List<String> qids, List<String> pids, BatchLog log,
                    StatementBatchCommitter committer) throws Exception {
                Map<String, Map<String, List<ApiStatement>>> answer = Map.of(
                        "P1001", Map.of("Q4164871", List.of(new ApiStatement(
                                "Q4164871$P1001", "Q171150", Map.of()))));
                committer.commit(qids, answer);
                return new PartialStatements(answer, 0, List.of());
            }
        };
        try (var store = new datasource.persistence.PersistentGraphStore(cacheDirectory)) {
            new WikidataGraphAdjacencyAcquisition(api, null, null, null)
                    .acquire(store, demand);
        }

        try (var restored = new datasource.persistence.PersistentGraphStore(cacheDirectory)) {
            assertEquals(List.of(EntityRef.wikidata("Q171150")),
                    restored.adjacent(demand).edges().stream()
                            .map(edge -> (EntityRef) edge.target()).toList());
            assertTrue(restored.adjacent(demand).missingNodes().isEmpty());
        }
    }

    @Test void compatibleOutgoingRelationsShareOneEntityPassAndKeepSeparateCoverage()
            throws Exception {
        EntityRef position = EntityRef.wikidata("Q6412254");
        GraphRelation jurisdiction = new GraphRelation("wikidata", "P1001");
        GraphRelation country = new GraphRelation("wikidata", "P17");
        GraphAdjacencyDemand jurisdictionDemand = new GraphAdjacencyDemand(
                List.of(position), jurisdiction, GraphTraversalDirection.OUTGOING);
        GraphAdjacencyDemand countryDemand = new GraphAdjacencyDemand(
                List.of(position), country, GraphTraversalDirection.OUTGOING);
        List<List<String>> requestedProperties = new ArrayList<>();
        List<String> logMessages = new ArrayList<>();
        WikidataApiClient api = new WikidataApiClient("test") {
            @Override public PartialStatements getStatementsByPropertyPartial(
                    List<String> qids, List<String> pids, BatchLog log,
                    StatementBatchCommitter committer) throws Exception {
                requestedProperties.add(List.copyOf(pids));
                Map<String, Map<String, List<ApiStatement>>> answer = Map.of(
                        "P1001", Map.of("Q6412254", List.of(new ApiStatement(
                                "Q6412254$P1001", "Q171150", Map.of()))),
                        "P17", Map.of("Q6412254", List.of(new ApiStatement(
                                "Q6412254$P17", "Q28", Map.of()))));
                committer.commit(qids, answer);
                return new PartialStatements(answer, 0, List.of());
            }
        };

        try (var store = new datasource.persistence.PersistentGraphStore(cacheDirectory)) {
            new WikidataGraphAdjacencyAcquisition(api, null,
                    wikidata.explore.extract.GenerationLog.of(logMessages::add), null)
                    .acquireAll(store, List.of(jurisdictionDemand, countryDemand));
        }

        try (var restored = new datasource.persistence.PersistentGraphStore(cacheDirectory)) {
            assertEquals(List.of(List.of("P1001", "P17")), requestedProperties);
            assertEquals(List.of("Acquire P1001 + P17 out — 1 node(s), "
                    + "1 initial request(s); adaptive splits may add requests\n"),
                    logMessages);
            assertEquals(List.of(EntityRef.wikidata("Q171150")), restored
                    .adjacent(jurisdictionDemand).edges().stream()
                    .map(edge -> (EntityRef) edge.target()).toList());
            assertEquals(List.of(EntityRef.wikidata("Q28")), restored
                    .adjacent(countryDemand).edges().stream()
                    .map(edge -> (EntityRef) edge.target()).toList());
            assertTrue(restored.adjacent(jurisdictionDemand).missingNodes().isEmpty());
            assertTrue(restored.adjacent(countryDemand).missingNodes().isEmpty());
        }
    }

    @Test void partiallyCachedRelationsRequestEachNodeOnceForOnlyItsMissingProperties()
            throws Exception {
        EntityRef first = EntityRef.wikidata("Q1");
        EntityRef second = EntityRef.wikidata("Q2");
        GraphRelation jurisdiction = new GraphRelation("wikidata", "P1001");
        GraphRelation country = new GraphRelation("wikidata", "P17");
        List<String> calls = new ArrayList<>();
        WikidataApiClient api = new WikidataApiClient("test") {
            @Override public PartialStatements getStatementsByPropertyPartial(
                    List<String> qids, List<String> pids, BatchLog log,
                    StatementBatchCommitter committer) throws Exception {
                calls.add(String.join("+", qids) + ":" + String.join("+", pids));
                committer.commit(qids, Map.of());
                return new PartialStatements(Map.of(), 0, List.of());
            }
        };
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphAdjacencyDemand jurisdictionMissing = new GraphAdjacencyDemand(
                List.of(first), jurisdiction, GraphTraversalDirection.OUTGOING);
        GraphAdjacencyDemand countryMissing = new GraphAdjacencyDemand(
                List.of(first, second), country, GraphTraversalDirection.OUTGOING);

        new WikidataGraphAdjacencyAcquisition(api, null, null, null)
                .acquireAll(store, List.of(jurisdictionMissing, countryMissing));

        assertEquals(List.of("Q1:P1001+P17", "Q2:P17"), calls);
        assertTrue(store.adjacent(jurisdictionMissing).missingNodes().isEmpty());
        assertTrue(store.adjacent(countryMissing).missingNodes().isEmpty());
    }

    @Test void aRelationThisProviderCannotFetchIsRefusedWithoutAnyClient() {
        // The executor asks the provider, so the provider has to answer. Refusing with
        // neither an API nor a SPARQL client configured is the point: validation
        // happens before anything is reachable to spend.
        GraphRelation foreign = new GraphRelation("dbpedia", "birthPlace");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new WikidataGraphAdjacencyAcquisition(null, null, null, null)
                        .validateRelations(List.of(
                                new GraphRelation("wikidata", "P279"), foreign)));

        assertTrue(refused.getMessage().contains(
                        "requires a property, statement:PID or qualifier:PID relation"),
                refused.getMessage());
    }

    @Test void shortIncomingPagesContinueUntilAnEmptyPageIsConfirmed()
            throws Exception {
        EntityRef position = EntityRef.wikidata("Q4164871");
        GraphRelation subclass = new GraphRelation("wikidata", "P279");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(position),
                subclass, GraphTraversalDirection.INCOMING);
        List<String> requests = new ArrayList<>();
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient() {
            int call;
            @Override public List<wikidata.WikidataBinding> query(String query) {
                requests.add(query);
                call++;
                if (call == 1) return List.of(
                        WikidataGraphAdjacencyAcquisitionTest.row(
                                "Q6412254", "Q4164871", "0001"));
                if (call == 2) return List.of(
                        WikidataGraphAdjacencyAcquisitionTest.row(
                                "Q6412255", "Q4164871", "0002"));
                return List.of();
            }
        };
        InMemoryGraphStore store = new InMemoryGraphStore();

        new WikidataGraphAdjacencyAcquisition(null, sparql, null, null)
                .acquire(store, demand);

        assertEquals(List.of(EntityRef.wikidata("Q6412254"),
                        EntityRef.wikidata("Q6412255")), store.adjacent(demand)
                .edges().stream().map(edge -> edge.source()).toList());
        assertEquals(GraphAdjacencyCoverage.COMPLETE,
                store.adjacencyKnowledge(position, demand));
        assertEquals(4, requests.size(),
                "two short pages, then an empty page and its one-row confirmation");
        assertTrue(requests.getFirst().contains(
                "ORDER BY STR(?statement)\nLIMIT 1000"));
        assertTrue(requests.get(1).contains("FILTER(STR(?statement) > \"")
                && requests.get(1).contains("0001"));
        assertTrue(requests.getLast().endsWith("LIMIT 1"));
        assertTrue(WikidataGraphAdjacencyAcquisition.incomingQuery(
                subclass, List.of(position)).contains("?subject wdt:P279 ?value"));
    }

    @Test void anIncomingRelationWithNoEdgesIsStillProvenComplete() throws Exception {
        EntityRef position = EntityRef.wikidata("Q4164871");
        GraphRelation subclass = new GraphRelation("wikidata", "P279");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(position),
                subclass, GraphTraversalDirection.INCOMING);
        List<String> requests = new ArrayList<>();
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient() {
            @Override public List<wikidata.WikidataBinding> query(String query) {
                requests.add(query);
                return List.of();
            }
        };
        InMemoryGraphStore store = new InMemoryGraphStore();

        new WikidataGraphAdjacencyAcquisition(null, sparql, null, null)
                .acquire(store, demand);

        assertEquals(2, requests.size(), "empty page plus one-row confirmation");
        assertEquals(GraphAdjacencyCoverage.COMPLETE,
                store.adjacencyKnowledge(position, demand));
    }

    @Test void aFailedLaterIncomingPageDoesNotCommitItsPrefixAsComplete()
            throws Exception {
        EntityRef position = EntityRef.wikidata("Q4164871");
        GraphRelation subclass = new GraphRelation("wikidata", "P279");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(position),
                subclass, GraphTraversalDirection.INCOMING);
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient() {
            @Override public List<wikidata.WikidataBinding> query(String query) {
                if (!query.contains("FILTER(STR(?statement) >")) {
                    return List.of(WikidataGraphAdjacencyAcquisitionTest.row(
                            "Q6412254", "Q4164871", "0001"));
                }
                throw new RuntimeException(new batch.ResponseTimeoutException(
                        "page timed out", null));
            }
        };
        InMemoryGraphStore store = new InMemoryGraphStore();

        new WikidataGraphAdjacencyAcquisition(null, sparql, null, null)
                .acquire(store, demand);

        assertTrue(store.adjacent(demand).edges().isEmpty(),
                "a prefix remains local to the page unit until every page succeeds");
        assertEquals(GraphAdjacencyCoverage.UNAVAILABLE,
                store.adjacencyKnowledge(position, demand));
    }

    @Test void aHeavyIncomingPageHalvesWithoutMovingItsCursor() throws Exception {
        EntityRef position = EntityRef.wikidata("Q4164871");
        GraphRelation subclass = new GraphRelation("wikidata", "P279");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(position),
                subclass, GraphTraversalDirection.INCOMING);
        List<String> requests = new ArrayList<>();
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient() {
            @Override public List<wikidata.WikidataBinding> query(String query) {
                requests.add(query);
                if (query.endsWith("LIMIT 1000")) {
                    throw new RuntimeException(new batch.ResponseTimeoutException(
                            "page timed out", null));
                }
                if (query.endsWith("LIMIT 500")
                        && !query.contains("FILTER(STR(?statement) >")) {
                    return List.of(WikidataGraphAdjacencyAcquisitionTest.row(
                            "Q6412254", "Q4164871", "0001"));
                }
                return List.of();
            }
        };
        InMemoryGraphStore store = new InMemoryGraphStore();

        new WikidataGraphAdjacencyAcquisition(null, sparql, null, null)
                .acquire(store, demand);

        assertEquals(List.of(1000, 500, 500, 1), requests.stream()
                .map(WikidataGraphAdjacencyAcquisitionTest::limitOf).toList());
        assertEquals(GraphAdjacencyCoverage.COMPLETE,
                store.adjacencyKnowledge(position, demand));
    }

    /** Halving a page shrinks the rows returned, not the ordering WDQS performs over the
     *  whole partition; a heavy partition of several targets splits its targets first. */
    @Test void aHeavySeveralTargetPartitionSplitsItsTargetsBeforeHalving()
            throws Exception {
        EntityRef first = EntityRef.wikidata("Q101");
        EntityRef second = EntityRef.wikidata("Q102");
        GraphRelation father = new GraphRelation("wikidata", "P22");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(first, second),
                father, GraphTraversalDirection.INCOMING);
        List<String> requests = new ArrayList<>();
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient() {
            @Override public List<wikidata.WikidataBinding> query(String query) {
                requests.add(query);
                if (query.contains("wd:Q101 wd:Q102")) {
                    throw new RuntimeException(new batch.ResponseTimeoutException(
                            "partition timed out", null));
                }
                return List.of();
            }
        };
        InMemoryGraphStore store = new InMemoryGraphStore();

        new WikidataGraphAdjacencyAcquisition(null, sparql, null, null)
                .acquire(store, demand);

        assertEquals(List.of(1000, 1000, 1, 1000, 1), requests.stream()
                .map(WikidataGraphAdjacencyAcquisitionTest::limitOf).toList(),
                "one failed partition page, then each target read at full page size");
        assertEquals(GraphAdjacencyCoverage.COMPLETE,
                store.adjacencyKnowledge(first, demand));
        assertEquals(GraphAdjacencyCoverage.COMPLETE,
                store.adjacencyKnowledge(second, demand));
    }

    /** An incoming edge used to carry no statement id, so a route that reached an entity
     *  through it could not name the claim that made it true. */
    @Test void anIncomingEdgeCarriesTheStatementThatStatesIt() throws Exception {
        EntityRef laocoon = EntityRef.wikidata("Q101");
        GraphRelation father = new GraphRelation("wikidata", "P22");
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient() {
            @Override public List<wikidata.WikidataBinding> query(String query) {
                return query.contains("FILTER(STR(?statement) >") ? List.of()
                        : List.of(WikidataGraphAdjacencyAcquisitionTest.row(
                                "Q201", "Q101", "4A2B-11EF-ABCD"));
            }
        };
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(laocoon), father,
                GraphTraversalDirection.INCOMING);

        new WikidataGraphAdjacencyAcquisition(null, sparql, null, null).acquire(store, demand);

        assertEquals(List.of("Q201$4A2B-11EF-ABCD"), store.adjacent(demand).edges().stream()
                .map(edge -> edge.provenanceId()).toList());
        String query = WikidataGraphAdjacencyAcquisition.incomingQuery(father,
                List.of(laocoon));
        assertTrue(query.contains("?subject wdt:P22 ?value .")
                && query.contains("?subject p:P22 ?statement .")
                && query.contains("?statement ps:P22 ?value ."), query);
    }

    private static wikidata.WikidataBinding row(
            String subject, String value, String statementSuffix) {
        return new wikidata.WikidataBinding(Map.of(
                "subject", "http://www.wikidata.org/entity/" + subject,
                "value", "http://www.wikidata.org/entity/" + value,
                "statement", "http://www.wikidata.org/entity/statement/"
                        + subject + "-" + statementSuffix));
    }

    private static int limitOf(String query) {
        return Integer.parseInt(query.substring(query.lastIndexOf("LIMIT ") + 6));
    }
}
