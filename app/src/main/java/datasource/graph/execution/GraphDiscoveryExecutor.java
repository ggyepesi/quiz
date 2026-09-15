package datasource.graph.execution;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphExpansionPolicy;
import datasource.graph.GraphTraversalDirection;
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
            condition.tests().forEach(test -> {
                relations.add(test.relation());
                // The generalising relation is asked for too, so an acquirer that cannot
                // supply it says so before the run is paid for rather than mid-way.
                if (test instanceof datasource.graph.constraint.GraphRelationReachesUnder under) {
                    relations.add(under.via());
                }
            });
        }
        return List.copyOf(relations);
    }

    private static void acquireEvidence(
            LocalGraphStore store,
            GraphAdjacencyAcquirer acquirer,
            List<EntityRef> candidates,
            GraphEvidenceCondition condition) throws Exception {
        LinkedHashSet<EntityRef> evidenceNodes = new LinkedHashSet<>();
        List<GraphAdjacencyDemand> evidenceDemands = condition.evidencePaths().stream()
                .map(path -> new GraphAdjacencyDemand(
                        candidates, path.relation(), path.direction()))
                .map(demand -> missingDemand(store, demand))
                .filter(java.util.Objects::nonNull)
                .toList();
        acquirer.acquireAll(store, evidenceDemands);
        for (GraphPath path : condition.evidencePaths()) {
            GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                    candidates, path.relation(), path.direction());
            store.adjacent(demand).edges().stream()
                    .map(edge -> edge.entityEndpoint(path.direction()))
                    .filter(java.util.Objects::nonNull)
                    .forEach(evidenceNodes::add);
        }
        List<EntityRef> evidence = List.copyOf(evidenceNodes);
        List<GraphAdjacencyDemand> testDemands = condition.tests().stream()
                .map(test -> new GraphAdjacencyDemand(
                        evidence, test.relation(), test.direction()))
                .distinct()
                .map(demand -> missingDemand(store, demand))
                .filter(java.util.Objects::nonNull)
                .toList();
        acquirer.acquireAll(store, testDemands);
        acquireGeneralisations(store, acquirer, condition, evidence);
    }

    /**
     * Fetches the generalisation hops a {@code reaches-under} test will walk.
     *
     * <p>The evaluator answers only from what the store holds, and treats an unfetched
     * hop as UNKNOWN rather than as a refusal — so without this every such test would
     * report REVIEW forever. One wave per hop, each asking only for what the previous
     * wave newly reached, bounded by the test's own depth.
     */
    private static void acquireGeneralisations(
            LocalGraphStore store,
            GraphAdjacencyAcquirer acquirer,
            GraphEvidenceCondition condition,
            List<EntityRef> evidence) throws Exception {
        for (var test : condition.tests()) {
            if (!(test instanceof datasource.graph.constraint.GraphRelationReachesUnder under)) {
                continue;
            }
            LinkedHashSet<EntityRef> seen = new LinkedHashSet<>();
            List<EntityRef> frontier = new java.util.ArrayList<>();
            GraphAdjacencyDemand reached = new GraphAdjacencyDemand(
                    evidence, under.relation(), under.direction());
            store.adjacent(reached).edges().stream()
                    .map(edge -> edge.entityEndpoint(under.direction()))
                    .filter(java.util.Objects::nonNull)
                    .filter(seen::add)
                    .forEach(frontier::add);
            for (int hop = 0; hop < under.maximumDepth() && !frontier.isEmpty(); hop++) {
                GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                        frontier, under.via(), GraphTraversalDirection.OUTGOING);
                acquireMissing(store, acquirer, demand);
                List<EntityRef> next = new java.util.ArrayList<>();
                for (GraphEdge edge : store.adjacent(demand).edges()) {
                    EntityRef value = edge.entityEndpoint(GraphTraversalDirection.OUTGOING);
                    if (value != null && seen.add(value)) next.add(value);
                }
                frontier = next;
            }
        }
    }

    private static void acquireMissing(
            LocalGraphStore store,
            GraphAdjacencyAcquirer acquirer,
            GraphAdjacencyDemand demand) throws Exception {
        GraphAdjacencyDemand missing = missingDemand(store, demand);
        if (missing != null) acquirer.acquire(store, missing);
    }

    private static GraphAdjacencyDemand missingDemand(
            LocalGraphStore store,
            GraphAdjacencyDemand demand) {
        List<EntityRef> missing = store.adjacent(demand).missingNodes();
        return missing.isEmpty() ? null : new GraphAdjacencyDemand(
                missing, demand.relation(), demand.direction());
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
