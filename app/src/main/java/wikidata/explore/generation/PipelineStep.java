package wikidata.explore.generation;

/**
 * One executable phase, and what it needs to be one.
 *
 * <p>A step declares its requirements rather than assuming them, which is what lets the
 * executor refuse before running instead of failing during: a graph that has not reached
 * the stage a step needs, or a step that would reach the network under a run forbidden
 * to, is a refusal the reader can read.
 *
 * <p>Steps do not reimplement anything. Finalization is still {@link DomainFinalization},
 * construction still {@link wikidata.explore.transform.StatementTransforms}, the semantic
 * worklist still {@link SemanticConvergence}. What is here is the contract those owners
 * are called through, so five flows stop each deciding the order for themselves.
 */
public interface PipelineStep {

    /** Which phase this is — the decision the planner made is looked up by it. */
    PipelinePhase phase();

    /** The stage the graph must already have reached. */
    GraphCheckpoint.Stage requires();

    /** The stage the graph has reached once this has run. */
    GraphCheckpoint.Stage produces();

    /**
     * What this step needs the network for — which is not the same as whether its phase
     * is an acquisition.
     *
     * <p>A boolean was wrong here, and wrongly enough to break Remap: the semantic
     * worklist always has local work — stamping roles, classifying kinds from stored
     * evidence, composing owned parts — and only MAY acquire on top of it. Refusing the
     * whole phase under acquisition NONE would refuse the local work too, which is how a
     * flow ends up reaching past the worklist for one of its pieces and skipping the two
     * steps that piece depends on.
     */
    enum NetworkUse {
        /** Purely local. Runs under any permission. */
        NONE,
        /**
         * Has a local subset, and does more when it is given something to acquire with.
         *
         * <p>Enforced by what the step HAS rather than by what it remembers not to call:
         * a context for a forbidden run carries no client, so the acquiring subset is
         * unreachable rather than merely unwise.
         */
        OPTIONAL,
        /** Cannot do anything without acquiring, so a forbidden run may not run it. */
        REQUIRED
    }

    /** Defaults to the phase's own answer, which is right for a step that only acquires. */
    default NetworkUse networkUse() {
        return phase().network() ? NetworkUse.REQUIRED : NetworkUse.NONE;
    }

    /** What this step did, for the log — written after it ran, so it can say how much. */
    String execute(PipelineContext context, PipelineState state) throws Exception;
}
