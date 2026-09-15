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
        } else if (condition instanceof GraphRelationReachesUnder under) {
            relation = under.relation();
            direction = under.direction();
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

        if (condition instanceof GraphRelationReachesUnder under) {
            return generalisation(store, adjacent, observation, direction, under);
        }
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

    /**
     * Whether any reached endpoint is the target or is generalised by it.
     *
     * <p>Coverage decides the difference between "no" and "not known yet". A walk that
     * runs out of KNOWN generalisations is only a refusal if every step it took was
     * complete; if any was partial the answer is REVIEW, because the hop that would have
     * matched may simply not have been fetched. Refusing on an unfetched hop is how a
     * condition quietly turns a gap in acquisition into a verdict about the world.
     */
    private static Evaluation generalisation(
            LocalGraphStore store,
            GraphAdjacencyResult adjacent,
            GraphAdjacencyObservation observation,
            GraphTraversalDirection direction,
            GraphRelationReachesUnder under) {
        List<GraphEdge> endpointEdges = adjacent.edges().stream()
                .filter(edge -> edge.entityEndpoint(direction) != null).toList();
        List<GraphEdge> witnesses = new java.util.ArrayList<>();
        boolean complete = true;
        for (GraphEdge edge : endpointEdges) {
            Reach reach = reaches(store, edge.entityEndpoint(direction), under);
            if (reach == Reach.YES) witnesses.add(edge);
            if (reach == Reach.UNKNOWN) complete = false;
        }
        if (!witnesses.isEmpty()) {
            return new Evaluation(
                    Decision.MATCHED, adjacent.edges(), List.copyOf(witnesses), observation);
        }
        if (!complete || observation.coverage() != GraphAdjacencyCoverage.COMPLETE) {
            return new Evaluation(
                    Decision.REVIEW, adjacent.edges(), List.of(), observation);
        }
        return new Evaluation(
                Decision.NOT_MATCHED, adjacent.edges(), List.of(), observation);
    }

    private enum Reach { YES, NO, UNKNOWN }

    /** Breadth-first over the generalising relation, bounded by the configured depth and
     *  by what the store already knows. Visited nodes are kept by identity of reference
     *  so a hierarchy that is a DAG — which P279 is — is not walked twice. */
    private static Reach reaches(
            LocalGraphStore store, EntityRef start, GraphRelationReachesUnder under) {
        if (under.entity().equals(start)) return Reach.YES;
        java.util.Set<EntityRef> visited = new java.util.LinkedHashSet<>();
        List<EntityRef> frontier = List.of(start);
        visited.add(start);
        boolean complete = true;
        for (int depth = 0; depth < under.maximumDepth() && !frontier.isEmpty(); depth++) {
            GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                    frontier, under.via(), GraphTraversalDirection.OUTGOING);
            GraphAdjacencyResult step = store.adjacent(demand);
            for (EntityRef node : frontier) {
                if (store.adjacencyKnowledge(node, demand)
                        != GraphAdjacencyCoverage.COMPLETE) {
                    complete = false;
                }
            }
            List<EntityRef> next = new java.util.ArrayList<>();
            for (GraphEdge edge : step.edges()) {
                EntityRef reached = edge.entityEndpoint(GraphTraversalDirection.OUTGOING);
                if (reached == null) continue;
                if (under.entity().equals(reached)) return Reach.YES;
                if (visited.add(reached)) next.add(reached);
            }
            frontier = next;
        }
        // Running out of depth IS a refusal. The bound is the scope the test declares —
        // "reaches this within four generalisations" — and a hierarchy like P279 never
        // exhausts, since everything has ancestors up to "entity". Treating an exhausted
        // bound as unknown therefore made NOT_MATCHED unreachable in practice: a first
        // run over 1,314 offices returned one refusal and 633 reviews, which decides
        // nothing. Only adjacency the store could not answer is genuinely unknown.
        return complete ? Reach.NO : Reach.UNKNOWN;
    }

    private static List<GraphEdge> matchingEdges(
            List<GraphEdge> edges,
            GraphTraversalDirection direction,
            GraphNodeCondition condition) {
        if (condition instanceof GraphRelationExists) return edges;
        if (condition instanceof GraphRelationReaches reaches) {
            return edges.stream().filter(edge -> reaches.entity()
                    .equals(edge.entityEndpoint(direction))).toList();
        }
        return List.of();
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
