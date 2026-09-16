package wikidata.explore.query.logical;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.execution.GraphDiscoveryExecutor;
import datasource.graph.store.GraphStoreProvider;
import datasource.graph.store.InMemoryGraphStore;
import datasource.graph.store.LocalGraphStore;
import datasource.persistence.PersistentGraphStore;
import wikidata.WikidataIds;
import wikidata.api.WikidataEntityLabelResolver;
import wikidata.explore.generation.WikidataGraphAdjacencyAcquisition;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.query.core.Datasource;
import wikidata.explore.query.core.WikidataAccess;
import work.Query;
import work.QueryContext;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Collection;

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
    private final String startClass;

    public ConfiguredGraphDiscoveryQuery(
            GeneratedProjectModel model, Collection<? extends objectview.Viewable> loadedInstances) {
        if (model == null || model.graphDiscoveryConfiguration() == null) {
            throw new IllegalArgumentException("Apply a discovery graph before running it");
        }
        configuration = model.graphDiscoveryConfiguration();
        String selectionName = configuration.startNode().populationSelection();
        List<String> qids;
        if (!selectionName.isBlank()) {
            var selected = model.findSelection(selectionName);
            if (!(selected instanceof PopulationSelection population)) {
                throw new IllegalArgumentException("The graph starts from population selection '"
                        + selectionName + "', which this model no longer has");
            }
            startClass = population.className();
            qids = population.instanceQids();
        } else {
            startClass = configuration.startNode().qidSourceClass();
            if (model.findClass(startClass) == null) {
                throw new IllegalArgumentException("The graph starts from class '"
                        + startClass + "', which this model no longer has");
            }
            qids = (loadedInstances == null ? List.<objectview.Viewable>of()
                    : loadedInstances.stream().map(objectview.Viewable.class::cast).toList())
                    .stream()
                    .filter(instance -> instance.directClassNames().contains(startClass))
                    .map(quiz.source.SourceIdentities::wikidataQid)
                    .filter(java.util.Objects::nonNull).toList();
        }
        start = qids.stream()
                .filter(WikidataIds::isQid).distinct()
                .map(EntityRef::wikidata).toList();
        if (start.isEmpty()) {
            throw new IllegalArgumentException(
                    selectionName.isBlank()
                            ? "No loaded " + configuration.startNode().qidSourceClass()
                                    + " instance has a Wikidata source QID"
                            : "Population selection '" + selectionName + "' has no instance QIDs");
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
        return Map.of("startClass", startClass,
                "populationSelection", configuration.startNode().populationSelection(),
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
                GraphStoreProvider provider = context.optional(GraphStoreProvider.class);
                try (LocalGraphStore store = provider == null
                        ? new InMemoryGraphStore() : provider.open()) {
                    GraphDiscoveryExecutor.Result graph = GraphDiscoveryExecutor.execute(
                            store, configuration, start, acquisition);
                    if (store instanceof PersistentGraphStore persistent) {
                        PersistentGraphStore.Statistics cache = persistent.statistics();
                        log.message("Downloaded graph facts: reused "
                                + cache.reusedAnswers() + " cached adjacency answer(s); saved "
                                + cache.savedAnswers() + " new answer(s)."
                                + (cache.discardedJournals() == 0 ? ""
                                : " Dropped the unusable tail of "
                                        + cache.discardedJournals()
                                        + " cached journal(s); that adjacency was"
                                        + " downloaded again.")
                                + "\n");
                    }
                    return result(graph, api, log, step);
                }
            }
        });
    }

    private Result result(
            GraphDiscoveryExecutor.Result graph,
            wikidata.api.WikidataApiClient api,
            wikidata.explore.extract.GenerationLog log,
            work.LogStep step) throws Exception {
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

    @Override public int rowCount(Result result) {
        if (result == null || result.graph() == null) return 0;
        return result.graph().nodes().stream()
                .mapToInt(node -> node.reached().size()).sum();
    }
    @Override public String summary(Result result) {
        return rowCount(result) + " reached graph node(s)";
    }
}
