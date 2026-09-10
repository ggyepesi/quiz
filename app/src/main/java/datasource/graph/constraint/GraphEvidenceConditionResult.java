package datasource.graph.constraint;

import datasource.EntityRef;
import datasource.graph.store.GraphEdge;

import java.util.List;

/** Auditable three-valued result of applying one evidence condition to one graph node. */
public record GraphEvidenceConditionResult(
        Decision decision,
        EntityRef node,
        String conditionName,
        GraphEvidenceCondition.ReviewDisposition reviewDisposition,
        List<GraphEdge> evidenceEdges,
        List<GraphEdge> testEdges,
        List<GraphEvidenceWitness> witnesses,
        List<GraphAdjacencyObservation> coverage,
        String reason) {

    public enum Decision { ACCEPTED, REJECTED, REVIEW }

    public GraphEvidenceConditionResult {
        if (decision == null || node == null || reviewDisposition == null) {
            throw new IllegalArgumentException(
                    "Decision, node and Review disposition are required");
        }
        conditionName = conditionName == null ? "" : conditionName.trim();
        evidenceEdges = immutable(evidenceEdges);
        testEdges = immutable(testEdges);
        witnesses = witnesses == null ? List.of() : List.copyOf(witnesses);
        coverage = coverage == null ? List.of() : List.copyOf(coverage);
        reason = reason == null ? "" : reason;
    }

    /**
     * Population disposition is explicit while the Review result remains inspectable.
     *
     * <p>Parenthesised deliberately: this decides whether an entity enters a domain,
     * and an added clause in an unparenthesised mix of {@code &&} and {@code ||} would
     * change that silently.
     */
    public boolean includedInPopulation() {
        return decision == Decision.ACCEPTED
                || (decision == Decision.REVIEW
                        && reviewDisposition
                            == GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT);
    }

    private static List<GraphEdge> immutable(List<GraphEdge> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
