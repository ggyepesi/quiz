package datasource.graph.store;

import datasource.EntityRef;
import datasource.GraphValue;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;

/** One provider-qualified directed relation retained by a local graph store. */
public record GraphEdge(
        EntityRef source,
        GraphRelation relation,
        GraphValue target,
        String provenanceId) {
    public GraphEdge {
        if (source == null || relation == null || target == null) {
            throw new IllegalArgumentException("Graph edge endpoints and relation are required");
        }
        provenanceId = provenanceId == null ? "" : provenanceId.trim();
    }

    /** The traversable endpoint in one direction, or null when the target is literal. */
    public EntityRef entityEndpoint(GraphTraversalDirection direction) {
        if (direction == null) return null;
        if (direction == GraphTraversalDirection.INCOMING) {
            return target instanceof EntityRef ? source : null;
        }
        return target instanceof EntityRef entity ? entity : null;
    }
}
