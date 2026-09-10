package datasource.graph.constraint;

import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;

/** Matches when covered adjacency contains at least one edge of this kind. */
public record GraphRelationExists(
        GraphRelation relation,
        GraphTraversalDirection direction) implements GraphNodeCondition {
    public GraphRelationExists {
        if (relation == null || direction == null) {
            throw new IllegalArgumentException("Present relation and direction are required");
        }
    }
}
