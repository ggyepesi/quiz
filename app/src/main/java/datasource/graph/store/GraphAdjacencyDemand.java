package datasource.graph.store;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;

import java.util.List;

/** Exact adjacency a wave needs to know for a set of focus nodes. */
public record GraphAdjacencyDemand(
        List<EntityRef> nodes,
        GraphRelation relation,
        GraphTraversalDirection direction) {
    public GraphAdjacencyDemand {
        nodes = nodes == null ? List.of() : nodes.stream().distinct().toList();
        if (relation == null || direction == null) {
            throw new IllegalArgumentException("Demand relation and direction are required");
        }
    }
}
