package wikidata.explore.query.logical;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.execution.GraphDiscoveryExecutor;
import datasource.graph.store.InMemoryGraphStore;
import wikidata.WikidataIds;
import wikidata.api.WikidataEntityLabelResolver;
import wikidata.explore.generation.WikidataGraphAdjacencyAcquisition;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.core.Datasource;
import wikidata.explore.query.core.WikidataAccess;
import work.Query;
import work.QueryContext;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Explicit, read-only execution of the graph saved in ModelBuilder configuration. */
public final class ConfiguredGraphDiscoveryQuery
        implements Query<ConfiguredGraphDiscoveryQuery.Result> {
    private static final int LABEL_LIMIT = 1_000;

    public record Result(
            GraphDiscoveryExecutor.Result graph,
            Map<String, String> labels,
            int labelledEntities) {
        public Result {
            labels = labels == null ? Map.of() : Map.copyOf(labels);
        }
        public String label(EntityRef entity) {
            return entity == null ? "" : labels.getOrDefault(entity.id(), entity.id());
        }
    }

    private final GraphDiscoveryConfiguration configuration;
    private final List<EntityRef> start;

    public ConfiguredGraphDiscoveryQuery(GeneratedProjectModel model) {
        if (model == null || model.graphDiscoveryConfiguration() == null) {
            throw new IllegalArgumentException("Apply a discovery graph before running it");
        }
        configuration = model.graphDiscoveryConfiguration();
        String startClass = configuration.startNode().qidSourceClass();
        GeneratedClassModel source = model.findClass(startClass);
        // A class can be deleted after the graph names it, and nothing validates the
        // reference until here. Saying it has no QIDs would send the reader to look
        // at QIDs on a class that is not there.
        if (source == null) {
            throw new IllegalArgumentException("The graph starts from class '"
                    + startClass + "', which this model no longer has");
        }
        start = source.seedQids().stream()
                .filter(WikidataIds::isQid).distinct()
                .map(EntityRef::wikidata).toList();
        if (start.isEmpty()) {
            throw new IllegalArgumentException(
                    "The start class '" + startClass + "' has no configured QIDs");
        }
    }

    @Override public String purpose() { return "Run configured graph discovery"; }
    @Override public String queryType() { return "Graph"; }
    @Override public String skeleton() {
        return "configured start QIDs -> graph waves -> evidence conditions";
    }
    @Override public String description() {
        return "Runs the saved graph without changing class populations or generation.";
    }
    @Override public Map<String, String> parameters() {
        return Map.of("startClass", configuration.startNode().qidSourceClass(),
                "startQids", String.valueOf(start.size()),
                "nodes", String.valueOf(configuration.nextNodes().size()));
    }

    @Override public Result execute(QueryContext context) throws Exception {
        return context.step(purpose(), queryType(), skeleton(), parameters(), step -> {
            var access = WikidataAccess.of(context);
            var api = access.api();
            if (api == null) throw new IllegalStateException(
                    "No Wikidata action API is configured for graph discovery");
            api.cancellation(context.cancellation());
            var log = StepGenerationLog.of(context, step);
            try (WikidataAccess.RequestLogs ignored =
                         WikidataAccess.logRequests(context, log::message)) {
                var acquisition = new WikidataGraphAdjacencyAcquisition(api,
                        access.sparql(Datasource.WIKIDATA), log, context.cancellation());
                GraphDiscoveryExecutor.Result graph = GraphDiscoveryExecutor.execute(
                        new InMemoryGraphStore(), configuration, start, acquisition);
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                graph.start().forEach(entity -> ids.add(entity.id()));
                graph.nodes().forEach(node -> node.reached()
                        .forEach(entity -> ids.add(entity.id())));
                List<String> labelled = ids.stream().limit(LABEL_LIMIT).toList();
                Map<String, String> labels = labelled.isEmpty() ? Map.of()
                        : new WikidataEntityLabelResolver(api).resolve(labelled,
                                WikidataEntityLabelResolver.Execution.SEQUENTIAL,
                                log.batchSink()).labels();
                int accepted = graph.nodes().stream()
                        .mapToInt(node -> node.accepted().size()).sum();
                int review = graph.nodes().stream()
                        .mapToInt(node -> node.review().size()).sum();
                step.summary(ids.size() + " distinct node(s); " + accepted
                        + " accepted, " + review + " review");
                return new Result(graph, new LinkedHashMap<>(labels), labelled.size());
            }
        });
    }

    @Override public int rowCount(Result result) {
        if (result == null || result.graph() == null) return 0;
        return result.graph().nodes().stream()
                .mapToInt(node -> node.reached().size()).sum();
    }
    @Override public String summary(Result result) {
        return rowCount(result) + " reached graph node(s)";
    }
}
