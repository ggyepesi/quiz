package wikidata.explore.generation;

import wikidata.explore.compiled.CompiledProjectModel;

/**
 * Canonicalize, prune dead stubs, validate, build vocabularies.
 *
 * <p>Runs on a settled graph and leaves a final one. It must come after acquisition
 * because "not fetched yet" and "dead" look the same from inside the pool, and pruning
 * the first as the second is how a run loses records it had asked for.
 */
public final class FinalizeStep implements PipelineStep {

    @Override public PipelinePhase phase() {
        return PipelinePhase.FINALIZE;
    }

    @Override public GraphCheckpoint.Stage requires() {
        return GraphCheckpoint.Stage.CONSTRUCTED_GRAPH;
    }

    @Override public GraphCheckpoint.Stage produces() {
        return GraphCheckpoint.Stage.FINAL_GRAPH;
    }

    @Override public String execute(PipelineContext context, PipelineState state)
            throws Exception {
        CompiledProjectModel compiled = context.run().model();
        DomainFinalization.Result result = DomainFinalization.apply(
                context.run().request().model(), compiled, state.pool(),
                state.records(), context.entityApi(), context.log());
        state.finalized(result);
        return result.dead() + " dead, " + result.orphans() + " orphan(s), "
                + result.requiredDropped() + " dropped for a missing required field";
    }
}
