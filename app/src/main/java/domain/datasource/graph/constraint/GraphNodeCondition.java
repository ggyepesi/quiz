package domain.datasource.graph.constraint;

import domain.datasource.graph.GraphRelation;
import domain.datasource.graph.GraphTraversalDirection;

/** A provider-neutral, coverage-aware condition evaluated against a reached graph node. */
public sealed interface GraphNodeCondition permits GraphRelationAbsent,
        GraphRelationExists, GraphRelationReaches, GraphRelationReachesUnder,
        GraphNodeIsUnder {
    GraphRelation relation();
    GraphTraversalDirection direction();
}
