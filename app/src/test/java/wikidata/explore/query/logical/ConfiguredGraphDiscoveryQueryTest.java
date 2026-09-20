package wikidata.explore.query.logical;

import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.store.GraphStoreProvider;
import datasource.persistence.PersistentGraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.FakeWikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.query.core.WikidataAccess;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Running the saved graph is an inspection operation, not population generation. */
class ConfiguredGraphDiscoveryQueryTest {
    @TempDir Path cacheDirectory;

    @Test void executionReadsConfiguredSeedsWithoutChangingTheModel() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.seedQids().add("Q999");
        model.rootClass(position);
        GeneratedClassModel graphClass = graphClass(model, "PositionGraph",
                new GraphDiscoveryConfiguration.StartNode("Position",
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(new GraphDiscoveryConfiguration.NextNode(
                        new GraphRelation("wikidata", "P279"),
                        GraphTraversalDirection.OUTGOING,
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY,
                        "", null)));
        WikidataApiClient api = new WikidataApiClient("test") {
            @Override public PartialStatements getStatementsByPropertyPartial(
                    List<String> qids, List<String> pids, BatchLog log,
                    StatementBatchCommitter committer) throws Exception {
                Map<String, Map<String, List<ApiStatement>>> statements =
                        Map.of("P279", Map.of("Q4164871", List.of(
                                new ApiStatement("Q4164871$P279", "Q1", Map.of()))));
                committer.commit(qids, statements);
                return new PartialStatements(statements, 0, List.of());
            }
            @Override public PartialEntities getEntitiesBestEffort(
                    List<String> qids, List<String> pids,
                    java.util.Collection<wikidata.api.FactDemand.EntityMetadata> metadata,
                    BatchLog log) {
                return new PartialEntities(Map.of(), 0, List.of());
            }
            @Override public Map<String, ApiEntity> getEntities(
                    List<String> qids, List<String> pids,
                    java.util.Collection<wikidata.api.FactDemand.EntityMetadata> metadata,
                    BatchLog log) {
                return Map.of();
            }
        };

        var result = new ConfiguredGraphDiscoveryQuery(model, graphClass,
                List.of(instance("Q4164871", "Position"))).execute(
                WikidataAccess.of(new FakeWikidataSparqlClient(), api).bind());

        assertEquals(List.of("Q1"), result.graph().nodes().getFirst().accepted()
                .stream().map(datasource.EntityRef::id).toList());
        assertEquals(List.of("Q999"), position.seedQids(),
                "class seeds are acquisition configuration, not the graph input");
        assertEquals(0, position.fields().size());
    }

    @Test void savedAdjacencyRunsThroughTheSameGraphWithoutAnotherWbgetentitiesRequest()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.seedQids().add("Q999");
        model.rootClass(position);
        GeneratedClassModel graphClass = graphClass(model, "PositionGraph",
                new GraphDiscoveryConfiguration.StartNode("Position",
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(new GraphDiscoveryConfiguration.NextNode(
                        new GraphRelation("wikidata", "P279"),
                        GraphTraversalDirection.OUTGOING,
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY,
                        "", null)));
        AtomicInteger requests = new AtomicInteger();
        WikidataApiClient api = new WikidataApiClient("test") {
            @Override public PartialStatements getStatementsByPropertyPartial(
                    List<String> qids, List<String> pids, BatchLog log,
                    StatementBatchCommitter committer) throws Exception {
                requests.incrementAndGet();
                Map<String, Map<String, List<ApiStatement>>> statements = Map.of(
                        "P279", Map.of("Q4164871", List.of(new ApiStatement(
                                "Q4164871$P279", "Q1", Map.of()))));
                committer.commit(qids, statements);
                return new PartialStatements(statements, 0, List.of());
            }
            @Override public Map<String, ApiEntity> getEntities(
                    List<String> qids, List<String> pids,
                    java.util.Collection<wikidata.api.FactDemand.EntityMetadata> metadata,
                    BatchLog log) {
                return Map.of();
            }
        };
        work.QueryContext context = WikidataAccess.of(
                        new FakeWikidataSparqlClient(), api).bind()
                .with(GraphStoreProvider.class,
                        (GraphStoreProvider) () -> new PersistentGraphStore(cacheDirectory));

        var acquired = new ConfiguredGraphDiscoveryQuery(model, graphClass,
                List.of(instance("Q4164871", "Position"))).execute(context);
        var repeated = new ConfiguredGraphDiscoveryQuery(model, graphClass,
                List.of(instance("Q4164871", "Position"))).execute(context);

        assertEquals(1, requests.get());
        assertEquals(acquired.graph(), repeated.graph(),
                "disk replay supplies the same executor; only acquisition is skipped");
        assertEquals(List.of("Q1"), repeated.graph().nodes().getFirst().accepted()
                .stream().map(datasource.EntityRef::id).toList());
    }

    @Test void aSavedPopulationSelectionIsTheExactGraphStart() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.seedQids().add("Q_CLASS_SEED_IS_NOT_A_QID");
        model.rootClass(position);
        PopulationSelection selected = new PopulationSelection("PositionsForHistory");
        selected.className("Position");
        selected.instanceQids(List.of("Q1", "Q2", "Q1"));
        model.addSelection(selected);
        GeneratedClassModel graphClass = graphClass(model, "PositionGraph",
                new GraphDiscoveryConfiguration.StartNode("", "PositionsForHistory",
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY), List.of());

        ConfiguredGraphDiscoveryQuery query =
                new ConfiguredGraphDiscoveryQuery(model, graphClass, List.of());

        assertEquals("2", query.parameters().get("startQids"));
        assertEquals("PositionsForHistory", query.parameters().get("populationSelection"));
    }

    /** A graph is declared by a class of kind GRAPH; the project no longer holds one. */
    private static GeneratedClassModel graphClass(GeneratedProjectModel model, String name,
            GraphDiscoveryConfiguration.StartNode start,
            List<GraphDiscoveryConfiguration.NextNode> nodes) {
        GeneratedClassModel graphClass = new GeneratedClassModel(name);
        graphClass.graphSource(new wikidata.explore.model.GraphClassSource(start, nodes));
        model.addClass(graphClass);
        return graphClass;
    }

    private static WikidataDynamicObject instance(String qid, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, qid);
        value.type(type);
        return value;
    }
}
