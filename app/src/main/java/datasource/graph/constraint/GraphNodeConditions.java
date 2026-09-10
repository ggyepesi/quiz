package datasource.graph.constraint;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.GraphAdjacencyResult;
import datasource.graph.store.GraphEdge;
import datasource.graph.store.LocalGraphStore;

import java.util.List;

/** One execution path for every coverage-aware condition on a reached graph node. */
final class GraphNodeConditions {
    private GraphNodeConditions() { }

    static Evaluation evaluate(
            LocalGraphStore store, EntityRef node, GraphNodeCondition condition) {
        if (condition == null) {
            return new Evaluation(Decision.MATCHED, List.of(), List.of(), null);
        }
        GraphRelation relation;
        GraphTraversalDirection direction;
        if (condition instanceof GraphRelationAbsent absent) {
            relation = absent.relation();
            direction = absent.direction();
        } else if (condition instanceof GraphRelationExists exists) {
            relation = exists.relation();
            direction = exists.direction();
        } else if (condition instanceof GraphRelationReaches reaches) {
            relation = reaches.relation();
            direction = reaches.direction();
        } else {
            throw new IllegalArgumentException(
                    "Unsupported graph node condition: " + condition);
        }

        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                List.of(node), relation, direction);
        GraphAdjacencyResult adjacent = store.adjacent(demand);
        GraphAdjacencyCoverage coverage = store.adjacencyKnowledge(node, demand);
        GraphAdjacencyObservation observation = new GraphAdjacencyObservation(
                node, relation, direction, coverage);

        List<GraphEdge> matches = matchingEdges(adjacent.edges(), direction, condition);
        if (!matches.isEmpty()) {
            return new Evaluation(
                    Decision.MATCHED, adjacent.edges(), matches, observation);
        }
        if (condition instanceof GraphRelationAbsent && !adjacent.edges().isEmpty()) {
            // One known edge disproves absence even if the rest of the adjacency is partial.
            return new Evaluation(
                    Decision.NOT_MATCHED, adjacent.edges(), List.of(), observation);
        }
        if (coverage == GraphAdjacencyCoverage.COMPLETE) {
            return condition instanceof GraphRelationAbsent
                    ? new Evaluation(Decision.MATCHED, List.of(), List.of(), observation)
                    : new Evaluation(Decision.NOT_MATCHED,
                            adjacent.edges(), List.of(), observation);
        }
        return new Evaluation(
                Decision.REVIEW, adjacent.edges(), List.of(), observation);
    }

    private static List<GraphEdge> matchingEdges(
            List<GraphEdge> edges,
            GraphTraversalDirection direction,
            GraphNodeCondition condition) {
        if (condition instanceof GraphRelationExists) return edges;
        if (condition instanceof GraphRelationReaches reaches) {
            return edges.stream().filter(edge -> endpoint(edge, direction)
                    .equals(reaches.entity())).toList();
        }
        return List.of();
    }

    static EntityRef endpoint(GraphEdge edge, GraphTraversalDirection direction) {
        return direction == GraphTraversalDirection.OUTGOING
                ? edge.target() : edge.source();
    }

    enum Decision { MATCHED, NOT_MATCHED, REVIEW }

    record Evaluation(
            Decision decision,
            List<GraphEdge> observedEdges,
            List<GraphEdge> witnessEdges,
            GraphAdjacencyObservation coverage) {
        Evaluation {
            observedEdges = observedEdges == null ? List.of() : List.copyOf(observedEdges);
            witnessEdges = witnessEdges == null ? List.of() : List.copyOf(witnessEdges);
        }
    }
}
