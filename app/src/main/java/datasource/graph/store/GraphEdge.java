package datasource.graph.store;

import datasource.EntityRef;
import datasource.graph.GraphRelation;

/** One provider-qualified directed edge retained by a local graph store. */
public record GraphEdge(
        EntityRef source,
        GraphRelation relation,
        EntityRef target,
        String provenanceId) {
    public GraphEdge {
        if (source == null || relation == null || target == null) {
            throw new IllegalArgumentException("Graph edge endpoints and relation are required");
        }
        provenanceId = provenanceId == null ? "" : provenanceId.trim();
    }
}
