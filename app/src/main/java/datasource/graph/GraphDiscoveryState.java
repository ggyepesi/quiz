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

    /**
     * Coverage for one exact traversal and terminal state.
     *
     * <p>Takes the edge contract rather than the statement pattern: what a node has
     * expanded is decided by the edge's identity, relation and direction, and a field
     * step has all three. Coverage of a field-derived walk is the same fact recorded the
     * same way, so it is not a second ledger.
     */
    public List<GraphExpansionCoverage> coverage(
            GraphEdgeDefinition edge, GraphExpansionCoverage.State state) {
        if (edge == null || state == null) return List.of();
        return coverage.stream().filter(item -> item.patternId().equals(edge.id()))
                .filter(item -> item.relation().equals(edge.relation()))
                .filter(item -> item.direction() == edge.direction())
                .filter(item -> item.state() == state)
                .toList();
    }

    public List<GraphExpansionCoverage> frontier(GraphEdgeDefinition edge) {
        return coverage(edge, GraphExpansionCoverage.State.ENCOUNTERED);
    }

    /** Queue a discovered node without turning execution history into model configuration. */
    public GraphDiscoveryState queue(String patternId, EntityRef node) {
        if (patternId == null) return this;
        return queue(patterns.stream()
                .filter(candidate -> patternId.equals(candidate.id()))
                .findFirst().orElse(null), node);
    }

    /**
     * Queues a node against the EDGE, not against an id looked up in the persisted
     * patterns. A field edge is derived from the model and never stored here, so
     * resolving by id could only ever find a statement pattern — the caller knows which
     * edge the decision came from and says so.
     */
    public GraphDiscoveryState queue(GraphEdgeDefinition pattern, EntityRef node) {
        if (pattern == null || node == null) return this;
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
        return reconcile(observed,
                observed == null ? List.of() : observed.patterns(), runComplete);
    }

    /**
     * Merge observed coverage while being told which configured edges actually ran.
     * Persisted patterns alone cannot answer that question: ordinary field edges are
     * compiled from the model and deliberately are not copied into this state.
     */
    public GraphDiscoveryState reconcile(GraphDiscoveryState observed,
            java.util.Collection<? extends GraphEdgeDefinition> activeEdges,
            boolean runComplete) {
        if (observed == null) observed = EMPTY;
        java.util.List<? extends GraphEdgeDefinition> active = activeEdges == null
                ? List.of() : List.copyOf(activeEdges);
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
            if (active.stream().noneMatch(edge -> samePattern(prior, edge))) {
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
            GraphExpansionCoverage item, GraphEdgeDefinition pattern, EntityRef node) {
        return samePattern(item, pattern) && item.node().equals(node);
    }

    private static boolean samePattern(
            GraphExpansionCoverage item, GraphEdgeDefinition pattern) {
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
