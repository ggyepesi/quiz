package datasource.graph.constraint;

import java.util.List;

/**
 * Admits one graph node by following alternative direct evidence paths and applying
 * alternative coverage-aware tests to the reached nodes.
 */
public record GraphEvidenceCondition(
        String name,
        List<GraphPath> evidencePaths,
        List<GraphNodeCondition> tests,
        ReviewDisposition reviewDisposition) {

    /**
     * What happens to an undecidable member; Review is always reported.
     *
     * <p>{@link #AWAIT_DECISION} is the default: the walk continues through the member so the
     * reviewer sees everything it reaches, but applying the result stops until every such
     * member has a manual decision — at the desktop and in a headless build alike, where it is
     * the build's AWAITING_DECISION state. The other two decide for the reviewer.
     */
    public enum ReviewDisposition {
        INCLUDE_AND_REPORT,
        EXCLUDE_AND_REPORT,
        AWAIT_DECISION
    }

    public GraphEvidenceCondition {
        name = name == null ? "" : name.trim();
        if (name.isBlank()) throw new IllegalArgumentException("Condition name is required");
        evidencePaths = evidencePaths == null ? List.of() : List.copyOf(evidencePaths);
        tests = tests == null ? List.of() : List.copyOf(tests);
        if (evidencePaths.isEmpty()) {
            throw new IllegalArgumentException("At least one evidence path is required");
        }
        if (evidencePaths.stream().anyMatch(path -> path == null || !path.isDirect())) {
            throw new IllegalArgumentException(
                    "The first evidence-condition slice supports direct paths only");
        }
        if (tests.isEmpty() || tests.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("At least one evidence test is required");
        }
        reviewDisposition = reviewDisposition == null
                ? ReviewDisposition.AWAIT_DECISION : reviewDisposition;
    }
}
