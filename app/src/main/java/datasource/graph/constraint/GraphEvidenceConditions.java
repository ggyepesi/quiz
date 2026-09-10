package datasource.graph.constraint;

import datasource.EntityRef;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.GraphAdjacencyResult;
import datasource.graph.store.GraphEdge;
import datasource.graph.store.LocalGraphStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evaluates the first bounded, any-of node-admission condition from retained graph facts. */
public final class GraphEvidenceConditions {
    private GraphEvidenceConditions() { }

    public static GraphEvidenceConditionResult evaluate(
            LocalGraphStore store, GraphEvidenceCondition condition, EntityRef node) {
        if (store == null || condition == null || node == null) {
            throw new IllegalArgumentException("Store, condition and node are required");
        }

        List<GraphEdge> evidenceEdges = new ArrayList<>();
        List<GraphAdjacencyObservation> coverage = new ArrayList<>();
        // Which first-hop edges reached each evidence node, so a witness can name the
        // relation that carried it. A node may be reached by several alternatives.
        Map<EntityRef, List<GraphEdge>> evidenceNodes = new LinkedHashMap<>();
        boolean evidenceIncomplete = false;

        for (GraphPath path : condition.evidencePaths()) {
            GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                    List.of(node), path.relation(), path.direction());
            GraphAdjacencyResult adjacent = store.adjacent(demand);
            GraphAdjacencyCoverage known = store.adjacencyKnowledge(node, demand);
            evidenceEdges.addAll(adjacent.edges());
            coverage.add(new GraphAdjacencyObservation(
                    node, path.relation(), path.direction(), known));
            evidenceIncomplete |= known != GraphAdjacencyCoverage.COMPLETE;
            for (GraphEdge edge : adjacent.edges()) {
                EntityRef reached = edge.entityEndpoint(path.direction());
                if (reached != null) {
                    evidenceNodes.computeIfAbsent(
                            reached, ignored -> new ArrayList<>()).add(edge);
                }
            }
        }

        if (evidenceNodes.isEmpty()) {
            return result(GraphEvidenceConditionResult.Decision.REVIEW,
                    node, condition, evidenceEdges, List.of(), List.of(), coverage,
                    evidenceIncomplete
                            ? "Evidence adjacency is incomplete or unavailable"
                            : "No configured evidence relation reaches a node");
        }

        List<GraphEdge> testEdges = new ArrayList<>();
        List<GraphEvidenceWitness> witnesses = new ArrayList<>();
        // Acceptance is the test's verdict, never the presence of a witness edge: an
        // absence test matches by there being no edge, so it accepts with an empty
        // witness list and its coverage observation as the record.
        boolean matched = false;
        boolean testsIncomplete = false;
        // Every evidence node is tested even once one has matched. A first-match exit
        // would decide the same outcome for less work — ANY is satisfied — but the
        // result would then name only one of the relations that justify the node, and
        // per-relation attribution is what the retained witness exists to support.
        // The cost is |evidence paths| + |evidence nodes| x |tests| local lookups.
        for (Map.Entry<EntityRef, List<GraphEdge>> reached : evidenceNodes.entrySet()) {
            for (GraphNodeCondition test : condition.tests()) {
                GraphNodeConditions.Evaluation evaluated =
                        GraphNodeConditions.evaluate(store, reached.getKey(), test);
                testEdges.addAll(evaluated.observedEdges());
                if (evaluated.coverage() != null) coverage.add(evaluated.coverage());
                if (evaluated.decision() == GraphNodeConditions.Decision.MATCHED) {
                    matched = true;
                    for (GraphEdge evidenceEdge : reached.getValue()) {
                        for (GraphEdge testEdge : evaluated.witnessEdges()) {
                            witnesses.add(new GraphEvidenceWitness(evidenceEdge, testEdge));
                        }
                    }
                } else if (evaluated.decision() == GraphNodeConditions.Decision.REVIEW) {
                    testsIncomplete = true;
                }
            }
        }

        if (matched) {
            return result(GraphEvidenceConditionResult.Decision.ACCEPTED,
                    node, condition, evidenceEdges, testEdges, witnesses, coverage,
                    "At least one evidence node satisfies a configured test");
        }
        if (evidenceIncomplete || testsIncomplete) {
            return result(GraphEvidenceConditionResult.Decision.REVIEW,
                    node, condition, evidenceEdges, testEdges, List.of(), coverage,
                    "Evidence or test adjacency is incomplete or unavailable");
        }
        return result(GraphEvidenceConditionResult.Decision.REJECTED,
                node, condition, evidenceEdges, testEdges, List.of(), coverage,
                "Evidence was found, but no evidence node satisfies a configured test");
    }

    private static GraphEvidenceConditionResult result(
            GraphEvidenceConditionResult.Decision decision,
            EntityRef node,
            GraphEvidenceCondition condition,
            List<GraphEdge> evidenceEdges,
            List<GraphEdge> testEdges,
            List<GraphEvidenceWitness> witnesses,
            List<GraphAdjacencyObservation> coverage,
            String reason) {
        return new GraphEvidenceConditionResult(
                decision, node, condition.name(), condition.reviewDisposition(),
                evidenceEdges, testEdges, witnesses, coverage, reason);
    }
}
