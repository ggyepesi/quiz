package datasource.graph.constraint;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;

/** Matches when traversing a relation in the configured direction reaches one entity. */
public record GraphRelationReaches(
        GraphRelation relation,
        GraphTraversalDirection direction,
        EntityRef entity) implements GraphNodeCondition {
    public GraphRelationReaches {
        if (relation == null || direction == null || entity == null) {
            throw new IllegalArgumentException(
                    "Reached relation, direction and entity are required");
        }
    }
}
