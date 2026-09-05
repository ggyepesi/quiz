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

    /** Whether running this can reach the network. */
    default boolean network() {
        return phase().network();
    }

    /** What this step did, for the log — written after it ran, so it can say how much. */
    String execute(PipelineContext context, PipelineState state) throws Exception;
}
