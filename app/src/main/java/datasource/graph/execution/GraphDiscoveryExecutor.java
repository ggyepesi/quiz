package datasource.graph.execution;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphExpansionPolicy;
import datasource.graph.GraphTraversalStep;
import datasource.graph.constraint.GraphEvidenceCondition;
import datasource.graph.constraint.GraphEvidenceConditionResult;
import datasource.graph.constraint.GraphEvidenceConditions;
import datasource.graph.constraint.GraphPath;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.GraphEdge;
import datasource.graph.store.LocalGraphStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Runs an authored linear graph by scheduling the existing local graph waves. */
public final class GraphDiscoveryExecutor {
    private GraphDiscoveryExecutor() { }

    public record NodeResult(
            int index,
            GraphDiscoveryConfiguration.NextNode configuration,
            GraphTraversalStep traversal,
            List<EntityRef> reached,
            List<EntityRef> accepted,
            List<EntityRef> rejected,
            List<EntityRef> review,
            List<GraphEvidenceConditionResult> classifications,
            List<GraphEdge> edges,
            List<EntityRef> incomplete,
            List<EntityRef> unavailable) {
        public NodeResult {
            reached = copy(reached); accepted = copy(accepted); rejected = copy(rejected);
            review = copy(review); edges = edges == null ? List.of() : List.copyOf(edges);
            classifications = classifications == null ? List.of()
                    : List.copyOf(classifications);
            incomplete = copy(incomplete); unavailable = copy(unavailable);
        }
        private static <T> List<T> copy(List<T> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    public record Result(List<EntityRef> start, List<NodeResult> nodes) {
        public Result {
            start = start == null ? List.of() : List.copyOf(start);
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }
    }

    public static Result execute(
            LocalGraphStore store,
            GraphDiscoveryConfiguration configuration,
            List<EntityRef> start,
            GraphAdjacencyAcquirer acquirer) throws Exception {
        if (store == null || configuration == null || acquirer == null) {
            throw new IllegalArgumentException(
                    "Store, graph configuration and adjacency acquirer are required");
        }
        acquirer.validateRelations(relationsOf(configuration));
        List<EntityRef> initial = distinct(start);
        List<EntityRef> frontier = initial;
        List<NodeResult> results = new ArrayList<>();
        int index = 0;
        for (GraphDiscoveryConfiguration.NextNode node : configuration.nextNodes()) {
            index++;
            GraphTraversalStep step = step(configuration, node, index);
            GraphWaveResult wave = GraphWave.evaluate(store, step, frontier);
            if (wave.requiresAcquisition()) {
                acquirer.acquire(store, wave.missingDemand());
                wave = GraphWave.evaluate(store, step, frontier);
            }

            List<EntityRef> accepted = new ArrayList<>();
            List<EntityRef> rejected = new ArrayList<>();
            List<EntityRef> review = new ArrayList<>();
            List<GraphEvidenceConditionResult> classifications = new ArrayList<>();
            GraphEvidenceCondition condition = node.evidenceCondition();
            if (condition == null) {
                accepted.addAll(wave.reached());
            } else {
                acquireEvidence(store, acquirer, wave.reached(), condition);
                for (EntityRef reached : wave.reached()) {
                    GraphEvidenceConditionResult classified =
                            GraphEvidenceConditions.evaluate(store, condition, reached);
                    classifications.add(classified);
                    switch (classified.decision()) {
                        case ACCEPTED -> accepted.add(reached);
                        case REJECTED -> rejected.add(reached);
                        case REVIEW -> review.add(reached);
                    }
                }
            }
            results.add(new NodeResult(index, node, step, wave.reached(), accepted,
                    rejected, review, classifications, wave.edges(), wave.incomplete(),
                    wave.unavailable()));
            frontier = condition == null ? List.copyOf(accepted)
                    : classifications.stream()
                            .filter(GraphEvidenceConditionResult::includedInPopulation)
                            .map(GraphEvidenceConditionResult::node).distinct().toList();
        }
        return new Result(initial, results);
    }

    /** Every relation this run could ask for: each edge, and each node's evidence
     *  paths and tests. Collected whole so the plan is checked before it is paid for. */
    private static List<datasource.graph.GraphRelation> relationsOf(
            GraphDiscoveryConfiguration configuration) {
        LinkedHashSet<datasource.graph.GraphRelation> relations = new LinkedHashSet<>();
        for (GraphDiscoveryConfiguration.NextNode node : configuration.nextNodes()) {
            relations.add(node.property());
            GraphEvidenceCondition condition = node.evidenceCondition();
            if (condition == null) continue;
            condition.evidencePaths().forEach(path -> relations.add(path.relation()));
            condition.tests().forEach(test -> relations.add(test.relation()));
        }
        return List.copyOf(relations);
    }

    private static void acquireEvidence(
            LocalGraphStore store,
            GraphAdjacencyAcquirer acquirer,
            List<EntityRef> candidates,
            GraphEvidenceCondition condition) throws Exception {
        LinkedHashSet<EntityRef> evidenceNodes = new LinkedHashSet<>();
        for (GraphPath path : condition.evidencePaths()) {
            GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                    candidates, path.relation(), path.direction());
            acquireMissing(store, acquirer, demand);
            store.adjacent(demand).edges().stream()
                    .map(edge -> edge.entityEndpoint(path.direction()))
                    .filter(java.util.Objects::nonNull)
                    .forEach(evidenceNodes::add);
        }
        for (var test : condition.tests()) {
            acquireMissing(store, acquirer, new GraphAdjacencyDemand(
                    List.copyOf(evidenceNodes), test.relation(), test.direction()));
        }
    }

    private static void acquireMissing(
            LocalGraphStore store,
            GraphAdjacencyAcquirer acquirer,
            GraphAdjacencyDemand demand) throws Exception {
        List<EntityRef> missing = store.adjacent(demand).missingNodes();
        if (!missing.isEmpty()) {
            acquirer.acquire(store, new GraphAdjacencyDemand(
                    missing, demand.relation(), demand.direction()));
        }
    }

    private static GraphTraversalStep step(
            GraphDiscoveryConfiguration graph,
            GraphDiscoveryConfiguration.NextNode node,
            int index) {
        String source = index == 1 ? graph.startNode().qidSourceClass()
                : "Graph node " + (index - 1);
        String target = node.use() == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION
                ? node.populationClass() : "Graph node " + index;
        return new GraphTraversalStep("configured-graph-" + index, source, target,
                "Configured graph", node.property(), node.directionFromPrevious(),
                GraphExpansionPolicy.CURATED);
    }

    private static List<EntityRef> distinct(List<EntityRef> values) {
        return values == null ? List.of() : List.copyOf(new LinkedHashSet<>(values));
    }
}
