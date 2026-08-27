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
    private final Map<CoverageKey, GraphAdjacencyCoverage> coverage = new LinkedHashMap<>();

    @Override public void addEdges(Collection<GraphEdge> values) {
        if (values != null) values.stream().filter(Objects::nonNull).forEach(edges::add);
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
        if (demand == null) return new GraphAdjacencyResult(List.of(), List.of(), List.of());
        Set<EntityRef> focus = new LinkedHashSet<>(demand.nodes());
        List<GraphEdge> found = edges.stream()
                .filter(edge -> edge.relation().equals(demand.relation()))
                .filter(edge -> demand.direction() == GraphTraversalDirection.OUTGOING
                        ? focus.contains(edge.source()) : focus.contains(edge.target()))
                .toList();
        List<EntityRef> missing = demand.nodes().stream()
                .filter(node -> adjacencyKnowledge(node, demand)
                        == GraphAdjacencyCoverage.UNKNOWN
                        || adjacencyKnowledge(node, demand)
                        == GraphAdjacencyCoverage.INCOMPLETE)
                .toList();
        List<EntityRef> unavailable = demand.nodes().stream()
                .filter(node -> adjacencyKnowledge(node, demand)
                        == GraphAdjacencyCoverage.UNAVAILABLE)
                .toList();
        return new GraphAdjacencyResult(found, missing, unavailable);
    }
}
