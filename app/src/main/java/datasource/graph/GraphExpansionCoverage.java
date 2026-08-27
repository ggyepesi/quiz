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

    public GraphExpansionCoverage {
        patternId = patternId == null ? "" : patternId.trim();
        if (patternId.isBlank()) throw new IllegalArgumentException("Pattern id is required");
        if (node == null) throw new IllegalArgumentException("Node is required");
        if (relation == null) throw new IllegalArgumentException("Relation is required");
        if (direction == null) throw new IllegalArgumentException("Direction is required");
        if (state == null) throw new IllegalArgumentException("State is required");
    }
}
