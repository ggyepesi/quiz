package domain.datasource.graph.execution;

import domain.datasource.EntityRef;
import domain.datasource.graph.store.GraphAdjacencyDemand;
import domain.datasource.graph.store.GraphEdge;

import java.util.List;

/** Local result of one wave, including the remote work it still requires. */
public record GraphWaveResult(
        List<EntityRef> reached,
        List<GraphEdge> edges,
        GraphAdjacencyDemand missingDemand,
        List<EntityRef> incomplete,
        List<EntityRef> unavailable) {
    public GraphWaveResult {
        reached = reached == null ? List.of() : List.copyOf(reached);
        edges = edges == null ? List.of() : List.copyOf(edges);
        incomplete = incomplete == null ? List.of() : List.copyOf(incomplete);
        unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
    }
    public boolean completeLocally() {
        return (missingDemand == null || missingDemand.nodes().isEmpty())
                && incomplete.isEmpty()
                && unavailable.isEmpty();
    }
    public boolean requiresAcquisition() {
        return missingDemand != null && !missingDemand.nodes().isEmpty();
    }
}
