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
     * Whether the walk continues through this entity to the next node.
     *
     * <p>Accepted entities continue; a Review entity continues unless its disposition
     * excludes it. One awaiting a decision continues too, so the reviewer sees everything
     * it reaches; it is not thereby in the population — applying the result waits for its
     * decision. It was called includedInPopulation while those two answers were the same.
     *
     * <p>Parenthesised deliberately: an added clause in an unparenthesised mix of
     * {@code &&} and {@code ||} would change this silently.
     */
    public boolean continuesTraversal() {
        return decision == Decision.ACCEPTED
                || (decision == Decision.REVIEW
                        && reviewDisposition
                            != GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT);
    }

    private static List<GraphEdge> immutable(List<GraphEdge> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
