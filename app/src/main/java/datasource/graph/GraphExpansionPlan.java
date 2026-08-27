package datasource.graph;

import java.util.List;

/** Compiled graph configuration: materializing patterns plus ordinary field steps. */
public record GraphExpansionPlan(
        List<GraphExpansionPattern> patterns,
        List<GraphTraversalStep> traversalSteps) {
    public static final GraphExpansionPlan EMPTY = new GraphExpansionPlan(List.of(), List.of());
    public GraphExpansionPlan {
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
        traversalSteps = traversalSteps == null ? List.of() : List.copyOf(traversalSteps);
    }
    public boolean isEmpty() {
        return patterns.isEmpty() && traversalSteps.isEmpty();
    }
}
