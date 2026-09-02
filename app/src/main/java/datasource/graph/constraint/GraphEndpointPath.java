package datasource.graph.constraint;

import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;

/** One bounded path used to derive comparable values from an endpoint. */
public record GraphEndpointPath(
        GraphRelation relation,
        GraphTraversalDirection direction,
        int maximumDepth,
        Selection selection,
        GraphNodeCondition condition) {

    public enum Selection {
        /** Compare every node reached within the bound. */
        ALL,
        /** Stop a branch at its first node satisfying {@link #condition}. */
        NEAREST_MATCHING
    }

    public GraphEndpointPath {
        if (relation == null || direction == null) {
            throw new IllegalArgumentException("Path relation and direction are required");
        }
        if (maximumDepth < 1) {
            throw new IllegalArgumentException("Path maximum depth must be at least one");
        }
        selection = selection == null ? Selection.ALL : selection;
        if (selection == Selection.NEAREST_MATCHING && condition == null) {
            throw new IllegalArgumentException(
                    "Nearest-matching traversal requires a node condition");
        }
    }

    public static GraphEndpointPath direct(
            GraphRelation relation, GraphTraversalDirection direction) {
        return new GraphEndpointPath(relation, direction, 1, Selection.ALL, null);
    }
}
