package datasource.graph.constraint;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.store.GraphAdjacencyCoverage;

/** Exact local adjacency knowledge retained with a graph-condition decision. */
public record GraphAdjacencyObservation(
        EntityRef node,
        GraphRelation relation,
        GraphTraversalDirection direction,
        GraphAdjacencyCoverage coverage) {
    public GraphAdjacencyObservation {
        if (node == null || relation == null || direction == null || coverage == null) {
            throw new IllegalArgumentException("A coverage observation must be complete");
        }
    }
}
