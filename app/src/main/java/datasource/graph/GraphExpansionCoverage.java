package datasource.graph;

import datasource.EntityRef;

/**
 * Final coverage for one node/edge/direction adjacency enumeration — what a saved
 * graph says was enumerated, as distinct from the transient request state a batch
 * journal owns while the work runs.
 */
public record GraphExpansionCoverage(
        String patternId,
        EntityRef node,
        GraphRelation relation,
        Direction direction,
        State state) {

    public enum Direction { INCOMING, OUTGOING }
    public enum State {
        ENCOUNTERED,
        /** Reserved for the durable execution ledger; not emitted by this slice. */
        QUEUED,
        /** Reserved for the durable execution ledger; not emitted by this slice. */
        EXPANDING,
        EXPANDED,
        /** Awaiting provider-level final coverage in the durable execution ledger. */
        INCOMPLETE
    }

    /**
     * Coverage for one enumeration round: every node the pattern has expanded, then
     * every node it reached without expanding. This is the ONE rule for what counts
     * as frontier, so a bounded preview and a generated snapshot cannot disagree
     * about it — they differ in how far they looked, never in what they mean.
     */
    public static java.util.List<GraphExpansionCoverage> of(
            GraphExpansionPattern pattern, Direction direction,
            java.util.Collection<datasource.EntityRef> expanded,
            java.util.Collection<datasource.EntityRef> reached) {
        java.util.Set<datasource.EntityRef> settled =
                new java.util.LinkedHashSet<>(expanded);
        java.util.List<GraphExpansionCoverage> out = new java.util.ArrayList<>();
        for (datasource.EntityRef node : settled) {
            out.add(new GraphExpansionCoverage(pattern.id(), node,
                    pattern.relation(), direction, State.EXPANDED));
        }
        for (datasource.EntityRef node : new java.util.LinkedHashSet<>(reached)) {
            if (settled.contains(node)) continue;
            out.add(new GraphExpansionCoverage(pattern.id(), node,
                    pattern.relation(), direction, State.ENCOUNTERED));
        }
        return java.util.List.copyOf(out);
    }

    public GraphExpansionCoverage {
        patternId = patternId == null ? "" : patternId.trim();
        if (patternId.isBlank()) throw new IllegalArgumentException("Pattern id is required");
        if (node == null) throw new IllegalArgumentException("Node is required");
        if (relation == null) throw new IllegalArgumentException("Relation is required");
        if (direction == null) throw new IllegalArgumentException("Direction is required");
        if (state == null) throw new IllegalArgumentException("State is required");
    }
}
