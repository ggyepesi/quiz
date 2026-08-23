package wikidata.explore.generation;

/** What each step of a run accounted for, written onto that step. */
public final class RunPhaseSummaries {

    private RunPhaseSummaries() {}

    /**
     * Appends what each step accounted for to that step's own summary.
     *
     * <p>Appends rather than replaces: the executor already wrote what the step
     * produced ("14904 statement record(s)"), and that is half the sentence. An
     * operation whose pipeline is a single step has no phase with these ids and simply
     * receives nothing — the alternative, inventing a step so the text has somewhere to
     * go, would put a stage in the plan that never ran.
     */
    public static void record(
            process.ProcessWorkflowPipeline pipeline,
            java.util.List<RuleEffects.Effect> effects) {

        if (pipeline == null || effects.isEmpty()) {
            return;
        }
        java.util.Map<String, String> existing = new java.util.LinkedHashMap<>();
        for (process.ProcessWorkflowPipeline.PhaseState state : pipeline.snapshot()) {
            existing.put(state.phase().id(), state.summary() == null ? "" : state.summary());
        }
        for (RuleEffects.RunPhase phase
                : RuleEffects.RunPhase.values()) {
            java.util.List<RuleEffects.Effect> mine =
                    RuleEffects.inPhase(effects, phase);
            if (mine.isEmpty() || !existing.containsKey(phase.pipelinePhaseId())) {
                continue;
            }
            String now = RuleEffects.summary(mine);
            pipeline.appendSummary(phase.pipelinePhaseId(), now);
        }
    }

    /** A single-step operation still keeps the whole account on the step it actually
     * ran. Logical effect phases remain available to result tabs without inventing
     * execution nodes that Remap or Enrich do not have yet. */
    public static void recordOperation(
            process.ProcessWorkflowPipeline pipeline, String operationPhaseId,
            java.util.List<RuleEffects.Effect> effects) {
        if (pipeline == null || operationPhaseId == null || effects == null
                || effects.isEmpty()) return;
        pipeline.appendSummary(operationPhaseId, RuleEffects.summary(effects));
    }
}
