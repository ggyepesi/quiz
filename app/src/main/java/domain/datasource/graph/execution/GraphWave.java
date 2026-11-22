package domain.datasource.graph.execution;

import domain.datasource.EntityRef;
import domain.datasource.graph.GraphTraversalDirection;
import domain.datasource.graph.GraphTraversalStep;
import domain.datasource.graph.store.*;

import java.util.List;

/**
 * Evaluates the locally known portion of one configured traversal step.
 *
 * <p>This class deliberately does not claim to be bounded: the caller schedules
 * waves and the provider adapter partitions each missing demand. It evaluates
 * exactly the input population it is given.</p>
 */
public final class GraphWave {
    private GraphWave() { }

    public static GraphWaveResult evaluate(
            LocalGraphStore store, GraphTraversalStep step, List<EntityRef> input) {
        if (store == null || step == null) {
            throw new IllegalArgumentException("Store and traversal step are required");
        }
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                input, step.relation(), step.direction());
        GraphAdjacencyResult local = store.adjacent(demand);
        List<EntityRef> reached = local.edges().stream()
                .map(edge -> edge.entityEndpoint(step.direction()))
                .filter(java.util.Objects::nonNull)
                .distinct().toList();
        GraphAdjacencyDemand missing = local.missingNodes().isEmpty() ? null
                : new GraphAdjacencyDemand(local.missingNodes(),
                        step.relation(), step.direction());
        return new GraphWaveResult(reached, local.edges(), missing,
                local.incompleteNodes(), local.unavailableNodes());
    }
}
