package datasource.graph.constraint;

import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;

/** A provider-neutral, coverage-aware condition evaluated against a reached graph node. */
public sealed interface GraphNodeCondition permits GraphRelationAbsent,
        GraphRelationExists, GraphRelationReaches {
    GraphRelation relation();
    GraphTraversalDirection direction();
}
