package datasource.graph.store;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;

import java.util.*;

/** Deterministic reference implementation used to prove wave semantics. */
public final class InMemoryGraphStore implements LocalGraphStore {
    private record CoverageKey(EntityRef node, GraphRelation relation,
                               GraphTraversalDirection direction) { }
    private final Set<GraphEdge> edges = new LinkedHashSet<>();
    private final Map<GraphRelation, Map<EntityRef, LinkedHashSet<GraphEdge>>> outgoing =
            new LinkedHashMap<>();
    private final Map<GraphRelation, Map<EntityRef, LinkedHashSet<GraphEdge>>> incoming =
            new LinkedHashMap<>();
    private final Map<CoverageKey, GraphAdjacencyCoverage> coverage = new LinkedHashMap<>();

    @Override public void addEdges(Collection<GraphEdge> values) {
        if (values == null) return;
        for (GraphEdge edge : values) {
            if (edge == null || !edges.add(edge)) continue;
            outgoing.computeIfAbsent(edge.relation(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(edge.source(), ignored -> new LinkedHashSet<>())
                    .add(edge);
            if (edge.target() instanceof EntityRef target) {
                incoming.computeIfAbsent(edge.relation(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(target, ignored -> new LinkedHashSet<>())
                        .add(edge);
            }
        }
    }

    @Override public void markCoverage(
            GraphAdjacencyDemand demand, GraphAdjacencyCoverage state) {
        if (demand == null || state == null) return;
        for (EntityRef node : demand.nodes()) {
            coverage.put(new CoverageKey(node, demand.relation(), demand.direction()), state);
        }
    }

    @Override public GraphAdjacencyCoverage adjacencyKnowledge(
            EntityRef node, GraphAdjacencyDemand demand) {
        if (node == null || demand == null) return GraphAdjacencyCoverage.UNKNOWN;
        return coverage.getOrDefault(new CoverageKey(
                node, demand.relation(), demand.direction()), GraphAdjacencyCoverage.UNKNOWN);
    }

    @Override public GraphAdjacencyResult adjacent(GraphAdjacencyDemand demand) {
        throwIfInterrupted();
        if (demand == null) {
            return new GraphAdjacencyResult(List.of(), List.of(), List.of(), List.of());
        }
        Map<EntityRef, LinkedHashSet<GraphEdge>> byEndpoint =
                (demand.direction() == GraphTraversalDirection.OUTGOING
                        ? outgoing : incoming).getOrDefault(demand.relation(), Map.of());
        LinkedHashSet<GraphEdge> found = new LinkedHashSet<>();
        for (EntityRef node : demand.nodes()) {
            throwIfInterrupted();
            found.addAll(byEndpoint.getOrDefault(node, new LinkedHashSet<>()));
        }
        List<EntityRef> missing = demand.nodes().stream()
                .filter(node -> adjacencyKnowledge(node, demand)
                        == GraphAdjacencyCoverage.UNKNOWN)
                .toList();
        List<EntityRef> incomplete = demand.nodes().stream()
                .filter(node -> adjacencyKnowledge(node, demand)
                        == GraphAdjacencyCoverage.INCOMPLETE)
                .toList();
        List<EntityRef> unavailable = demand.nodes().stream()
                .filter(node -> adjacencyKnowledge(node, demand)
                        == GraphAdjacencyCoverage.UNAVAILABLE)
                .toList();
        return new GraphAdjacencyResult(List.copyOf(found), missing, incomplete, unavailable);
    }

    /** A large local graph scan is running process work, so the worker interrupt must
     * stop it just as it stops a remote request. */
    private static void throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException(
                    "Graph scan cancelled");
        }
    }
}
