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

    public List<GraphExpansionCoverage> frontier(String patternId) {
        return coverage.stream().filter(item -> item.patternId().equals(patternId))
                .filter(item -> item.state() == GraphExpansionCoverage.State.ENCOUNTERED)
                .toList();
    }
}
