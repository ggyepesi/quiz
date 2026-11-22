package domain.datasource.graph.constraint;

import domain.datasource.EntityRef;
import domain.datasource.graph.GraphRelation;
import domain.datasource.graph.GraphTraversalDirection;
import domain.datasource.graph.store.GraphAdjacencyCoverage;

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
