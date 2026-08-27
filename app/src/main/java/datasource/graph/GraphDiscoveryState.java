package datasource.graph;

import java.util.List;

/** Persisted graph patterns and their node-relative expansion coverage. */
public record GraphDiscoveryState(
        List<GraphExpansionPattern> patterns,
        List<GraphExpansionCoverage> coverage) {

    public static final GraphDiscoveryState EMPTY = new GraphDiscoveryState(List.of(), List.of());

    public GraphDiscoveryState {
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
        coverage = coverage == null ? List.of() : List.copyOf(coverage);
    }

    /** Coverage for one exact traversal and terminal state. */
    public List<GraphExpansionCoverage> coverage(
            GraphExpansionPattern pattern, GraphExpansionCoverage.State state) {
        if (pattern == null || state == null) return List.of();
        return coverage.stream().filter(item -> item.patternId().equals(pattern.id()))
                .filter(item -> item.relation().equals(pattern.relation()))
                .filter(item -> item.direction() == pattern.direction())
                .filter(item -> item.state() == state)
                .toList();
    }

    public List<GraphExpansionCoverage> frontier(GraphExpansionPattern pattern) {
        return coverage(pattern, GraphExpansionCoverage.State.ENCOUNTERED);
    }
}
