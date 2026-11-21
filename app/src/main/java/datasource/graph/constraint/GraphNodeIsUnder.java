package datasource.graph.constraint;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;

/**
 * Matches when the evidence node is {@code entity}, or generalises to it through
 * {@code via} within the configured number of hops.
 *
 * <p>This is deliberately about the evidence node itself. It complements
 * {@link GraphRelationReachesUnder}, which first follows a separate relation from the
 * evidence node and tests the entities reached by that relation.</p>
 */
public record GraphNodeIsUnder(
        EntityRef entity,
        GraphRelation via,
        int maximumDepth) implements GraphNodeCondition {

    public static final int DEFAULT_MAXIMUM_DEPTH = 4;

    public GraphNodeIsUnder {
        if (entity == null || via == null) {
            throw new IllegalArgumentException(
                    "Target entity and generalising relation are required");
        }
        if (maximumDepth < 1) {
            throw new IllegalArgumentException(
                    "Generalisation depth must be at least one");
        }
    }

    public static GraphNodeIsUnder of(EntityRef entity, GraphRelation via) {
        return new GraphNodeIsUnder(entity, via, DEFAULT_MAXIMUM_DEPTH);
    }

    @Override public GraphRelation relation() {
        return via;
    }

    @Override public GraphTraversalDirection direction() {
        return GraphTraversalDirection.OUTGOING;
    }
}
