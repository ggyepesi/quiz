package datasource.graph.constraint;

import datasource.EntityRef;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.GraphAdjacencyResult;
import datasource.graph.store.GraphEdge;
import datasource.graph.store.LocalGraphStore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Evaluates endpoint compatibility using only covered facts in a local graph store. */
public final class GraphEndpointConstraints {
    private GraphEndpointConstraints() { }

    public static GraphEndpointConstraintResult evaluate(
            LocalGraphStore store,
            GraphEndpointConstraint constraint,
            EntityRef left,
            EntityRef right) {
        if (store == null || constraint == null || left == null || right == null) {
            throw new IllegalArgumentException("Store, constraint and both endpoints are required");
        }
        PathResult leftResult = follow(store, left, constraint.leftPath());
        PathResult rightResult = follow(store, right, constraint.rightPath());
        Set<EntityRef> shared = new LinkedHashSet<>(leftResult.values());
        shared.retainAll(rightResult.values());
        boolean selectionUnresolved =
                constraint.leftPath().selection()
                        == GraphPath.Selection.NEAREST_MATCHING
                        && leftResult.unresolved()
                || constraint.rightPath().selection()
                        == GraphPath.Selection.NEAREST_MATCHING
                        && rightResult.unresolved();
        if (!shared.isEmpty() && !selectionUnresolved) {
            return result(GraphEndpointConstraintResult.Decision.ACCEPTED,
                    leftResult, rightResult, shared,
                    "Endpoint paths share " + shared.size() + " value(s)");
        }
        if (leftResult.unresolved() || rightResult.unresolved()) {
            GraphEndpointConstraintResult.Decision decision =
                    constraint.missingPolicy() == GraphEndpointConstraint.MissingPolicy.REVIEW
                            ? GraphEndpointConstraintResult.Decision.REVIEW
                            : GraphEndpointConstraintResult.Decision.REJECTED;
            return result(decision, leftResult, rightResult, Set.of(),
                    "One or both endpoint paths lack complete adjacency evidence");
        }
        return result(GraphEndpointConstraintResult.Decision.REJECTED,
                leftResult, rightResult, Set.of(),
                "Endpoint paths have no shared value");
    }

    private static GraphEndpointConstraintResult result(
            GraphEndpointConstraintResult.Decision decision,
            PathResult left, PathResult right, Set<EntityRef> shared, String reason) {
        return new GraphEndpointConstraintResult(
                decision, left.values(), right.values(), shared, reason);
    }

    private static PathResult follow(
            LocalGraphStore store, EntityRef start, GraphPath path) {
        Set<EntityRef> values = new LinkedHashSet<>();
        Set<EntityRef> frontier = new LinkedHashSet<>(List.of(start));
        Set<EntityRef> visited = new LinkedHashSet<>(frontier);
        boolean unresolved = false;
        for (int depth = 1; depth <= path.maximumDepth() && !frontier.isEmpty(); depth++) {
            GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                    List.copyOf(frontier), path.relation(), path.direction());
            GraphAdjacencyResult adjacent = store.adjacent(demand);
            unresolved |= !adjacent.missingNodes().isEmpty()
                    || !adjacent.incompleteNodes().isEmpty()
                    || !adjacent.unavailableNodes().isEmpty();
            Set<EntityRef> reached = endpoints(adjacent.edges(), path.direction());
            Set<EntityRef> next = new LinkedHashSet<>();
            for (EntityRef node : reached) {
                GraphNodeConditions.Evaluation condition =
                        GraphNodeConditions.evaluate(store, node, path.condition());
                unresolved |= condition.decision() == GraphNodeConditions.Decision.REVIEW;
                if (path.selection() == GraphPath.Selection.ALL) {
                    if (condition.decision() == GraphNodeConditions.Decision.MATCHED) {
                        values.add(node);
                    }
                    if (visited.add(node)) next.add(node);
                } else if (condition.decision() == GraphNodeConditions.Decision.MATCHED) {
                    values.add(node); // stop this branch at its nearest qualifying node
                } else if (visited.add(node)) {
                    next.add(node);
                }
            }
            frontier = next;
        }
        return new PathResult(Set.copyOf(values), unresolved);
    }

    private static Set<EntityRef> endpoints(
            List<GraphEdge> edges, GraphTraversalDirection direction) {
        Set<EntityRef> result = new LinkedHashSet<>();
        for (GraphEdge edge : edges) {
            EntityRef endpoint = edge.entityEndpoint(direction);
            if (endpoint != null) result.add(endpoint);
        }
        return result;
    }

    private record PathResult(Set<EntityRef> values, boolean unresolved) { }
}
