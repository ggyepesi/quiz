package datasource.graph.store;

import datasource.EntityRef;

import java.util.List;

public record GraphAdjacencyResult(
        List<GraphEdge> edges,
        List<EntityRef> missingNodes,
        List<EntityRef> unavailableNodes) {
    public GraphAdjacencyResult {
        edges = edges == null ? List.of() : List.copyOf(edges);
        missingNodes = missingNodes == null ? List.of() : List.copyOf(missingNodes);
        unavailableNodes = unavailableNodes == null ? List.of() : List.copyOf(unavailableNodes);
    }
    public boolean requiresAcquisition() { return !missingNodes.isEmpty(); }
}
