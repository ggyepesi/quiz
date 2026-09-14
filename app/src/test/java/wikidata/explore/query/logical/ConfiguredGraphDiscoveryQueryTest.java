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
        position.seedQids().add("Q4164871");
        model.rootClass(position);
        model.graphDiscoveryConfiguration(new GraphDiscoveryConfiguration(
                new GraphDiscoveryConfiguration.StartNode("Position",
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(new GraphDiscoveryConfiguration.NextNode(
                        new GraphRelation("wikidata", "P279"),
                        GraphTraversalDirection.OUTGOING,
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY,
                        "", null))));
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

        var result = new ConfiguredGraphDiscoveryQuery(model).execute(
                WikidataAccess.of(new FakeWikidataSparqlClient(), api).bind());

        assertEquals(List.of("Q1"), result.graph().nodes().getFirst().accepted()
                .stream().map(datasource.EntityRef::id).toList());
        assertEquals(List.of("Q4164871"), position.seedQids());
        assertEquals(0, position.fields().size());
    }

    @Test void aSecondGraphExperimentReadsCompletedAdjacencyWithoutAnotherRequest()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.seedQids().add("Q4164871");
        model.rootClass(position);
        model.graphDiscoveryConfiguration(new GraphDiscoveryConfiguration(
                new GraphDiscoveryConfiguration.StartNode("Position",
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(new GraphDiscoveryConfiguration.NextNode(
                        new GraphRelation("wikidata", "P279"),
                        GraphTraversalDirection.OUTGOING,
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY,
                        "", null))));
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

        new ConfiguredGraphDiscoveryQuery(model).execute(context);
        var repeated = new ConfiguredGraphDiscoveryQuery(model).execute(context);

        assertEquals(1, requests.get());
        assertEquals(List.of("Q1"), repeated.graph().nodes().getFirst().accepted()
                .stream().map(datasource.EntityRef::id).toList());
    }
}
