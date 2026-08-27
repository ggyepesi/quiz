package datasource.graph;

import datasource.EntityRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** Queue a discovered node without turning execution history into model configuration. */
    public GraphDiscoveryState queue(String patternId, EntityRef node) {
        if (patternId == null || node == null) return this;
        GraphExpansionPattern pattern = patterns.stream()
                .filter(candidate -> patternId.equals(candidate.id()))
                .findFirst().orElse(null);
        if (pattern == null) return this;
        List<GraphExpansionCoverage> next = new ArrayList<>(coverage.size() + 1);
        boolean replaced = false;
        for (GraphExpansionCoverage item : coverage) {
            if (sameKey(item, pattern, node)) {
                next.add(new GraphExpansionCoverage(pattern.id(), node, pattern.relation(),
                        pattern.direction(), GraphExpansionCoverage.State.QUEUED));
                replaced = true;
            } else {
                next.add(item);
            }
        }
        if (!replaced) {
            next.add(new GraphExpansionCoverage(pattern.id(), node, pattern.relation(),
                    pattern.direction(), GraphExpansionCoverage.State.QUEUED));
        }
        return new GraphDiscoveryState(patterns, next);
    }

    /**
     * Merge a completed generation's observed graph with the durable execution ledger.
     * A queued retry becomes expanded only when the run itself is complete; otherwise it
     * remains explicit work rather than masquerading as settled coverage.
     */
    public GraphDiscoveryState reconcile(GraphDiscoveryState observed, boolean runComplete) {
        if (observed == null) observed = EMPTY;
        List<GraphExpansionPattern> reconciledPatterns = new ArrayList<>(observed.patterns);
        for (GraphExpansionPattern priorPattern : patterns) {
            if (reconciledPatterns.stream().noneMatch(candidate ->
                    samePattern(priorPattern, candidate))) {
                reconciledPatterns.add(priorPattern);
            }
        }
        Map<Key, GraphExpansionCoverage> merged = new LinkedHashMap<>();
        observed.coverage.forEach(item -> merged.put(Key.of(item), item));
        for (GraphExpansionCoverage prior : coverage) {
            // A disabled pattern is dormant, not deleted. It did not execute in this
            // run, so neither its coverage nor its queued state may change. Re-enabling
            // the policy must resume from the same durable ledger.
            if (observed.patterns.stream().noneMatch(pattern -> samePattern(prior, pattern))) {
                merged.putIfAbsent(Key.of(prior), prior);
                continue;
            }
            Key key = Key.of(prior);
            GraphExpansionCoverage now = merged.get(key);
            if (prior.state() == GraphExpansionCoverage.State.QUEUED
                    || prior.state() == GraphExpansionCoverage.State.INCOMPLETE) {
                GraphExpansionCoverage.State state = runComplete && now != null
                        && now.state() == GraphExpansionCoverage.State.EXPANDED
                        ? GraphExpansionCoverage.State.EXPANDED
                        : GraphExpansionCoverage.State.INCOMPLETE;
                merged.put(key, withState(prior, state));
            } else if (now == null) {
                merged.put(key, prior);
            }
        }
        return new GraphDiscoveryState(reconciledPatterns, List.copyOf(merged.values()));
    }

    private static GraphExpansionCoverage withState(
            GraphExpansionCoverage item, GraphExpansionCoverage.State state) {
        return new GraphExpansionCoverage(item.patternId(), item.node(), item.relation(),
                item.direction(), state);
    }

    private static boolean sameKey(
            GraphExpansionCoverage item, GraphExpansionPattern pattern, EntityRef node) {
        return samePattern(item, pattern) && item.node().equals(node);
    }

    private static boolean samePattern(
            GraphExpansionCoverage item, GraphExpansionPattern pattern) {
        return item.patternId().equals(pattern.id())
                && item.relation().equals(pattern.relation())
                && item.direction() == pattern.direction();
    }

    private static boolean samePattern(
            GraphExpansionPattern first, GraphExpansionPattern second) {
        return first.id().equals(second.id())
                && first.relation().equals(second.relation())
                && first.direction() == second.direction();
    }

    private record Key(String patternId, EntityRef node, GraphRelation relation,
                       GraphTraversalDirection direction) {
        static Key of(GraphExpansionCoverage item) {
            return new Key(item.patternId(), item.node(), item.relation(), item.direction());
        }
    }
}
