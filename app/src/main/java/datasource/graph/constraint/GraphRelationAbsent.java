package datasource.graph.constraint;

import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;

/** Matches when covered adjacency proves that a node has no edge of this kind. */
public record GraphRelationAbsent(
        GraphRelation relation,
        GraphTraversalDirection direction) implements GraphNodeCondition {
    public GraphRelationAbsent {
        if (relation == null || direction == null) {
            throw new IllegalArgumentException("Absent relation and direction are required");
        }
    }
}
