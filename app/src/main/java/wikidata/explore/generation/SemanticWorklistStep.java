package wikidata.explore.generation;

/**
 * Stamp roles, load what they declare, settle kinds, compose owned parts — until stable.
 *
 * <p>{@link PipelineStep.NetworkUse#OPTIONAL}, and this is the step that made a boolean
 * wrong. Its local work — stamping, classifying from stored evidence, composing parts —
 * needs nothing fetched, so a run forbidden to acquire still runs it and converges. What
 * that run does without is loading declarations it has not got and asking about entities
 * whose evidence it does not hold, which is the acquisition and not the phase.
 *
 * <p>The subset is chosen by what the context HAS. A forbidden run carries no client, so
 * {@link SemanticConvergence} takes its local path — the acquiring half is unreachable
 * rather than merely skipped by agreement.
 */
public final class SemanticWorklistStep implements PipelineStep {

    private final datasource.api.SourceExecutionPlan sourcePlan;
    private final GenerationQualityTracker quality;

    public SemanticWorklistStep() {
        this(null, null);
    }

    public SemanticWorklistStep(
            datasource.api.SourceExecutionPlan sourcePlan, GenerationQualityTracker quality) {
        this.sourcePlan = sourcePlan;
        this.quality = quality;
    }

    @Override public PipelinePhase phase() {
        return PipelinePhase.RESOLVE_SEMANTIC_WORKLIST;
    }

    @Override public GraphCheckpoint.Stage requires() {
        return GraphCheckpoint.Stage.CONSTRUCTED_GRAPH;
    }

    @Override public GraphCheckpoint.Stage produces() {
        return GraphCheckpoint.Stage.CONSTRUCTED_GRAPH;
    }

    @Override public NetworkUse networkUse() {
        return NetworkUse.OPTIONAL;
    }

    @Override public String execute(PipelineContext context, PipelineState state) {
        SemanticConvergence.Result converged = SemanticConvergence.apply(
                context.run().request().model(), state.pool(), state.evidence(),
                context.entityApi(), context.log(), state.loadedDeclarations(), quality,
                sourcePlan);
        state.converged(converged);
        state.loadedDeclarations().clear();
        state.loadedDeclarations().addAll(converged.completedDeclarations().values());
        return converged.ownedCreated() + " owned part(s), "
                + converged.classifiedKinds() + " kind(s), "
                + converged.loadedFields() + " field value(s)"
                + (context.entityApi() == null ? " (local only)" : "");
    }
}
