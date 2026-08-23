package wikidata.explore.generation;

import process.Process;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessPlan;
import process.ProcessStatus;
import process.ProcessWorkflowPipeline;
import process.QuerySubprocess;
import work.Query;

import java.util.Map;

/** Shared process façade for staged generation operations backed by an existing query. */
public final class GenerationOperationProcess implements Process<GenerationRun> {
    private final String title;
    private final String description;
    private final String phaseId;
    private final Query<GenerationRun> query;
    private final ProcessWorkflowPipeline pipeline;
    private final GenerationExecutionSettings settings;
    /** Whether this operation fetches at all. Remap re-runs transforms over the pool it
     *  already has, so reporting a cache budget and a request concurrency for it
     *  describes resources nothing allocates. */
    private final boolean networked;

    public GenerationOperationProcess(
            String title, String description, String phaseId,
            Query<GenerationRun> query, ProcessWorkflowPipeline pipeline) {
        this(title, description, phaseId, query, pipeline,
                new GenerationExecutionSettings(), true);
    }

    public GenerationOperationProcess(
            String title, String description, String phaseId,
            Query<GenerationRun> query, ProcessWorkflowPipeline pipeline,
            GenerationExecutionSettings settings) {
        this(title, description, phaseId, query, pipeline, settings, true);
    }

    public GenerationOperationProcess(
            String title, String description, String phaseId,
            Query<GenerationRun> query, ProcessWorkflowPipeline pipeline,
            GenerationExecutionSettings settings, boolean networked) {
        this.networked = networked;
        this.title = title;
        this.description = description;
        this.phaseId = phaseId;
        this.query = query;
        this.pipeline = pipeline;
        this.settings = settings;
    }

    @Override public ProcessPlan plan() {
        Map<String, String> parameters = new java.util.LinkedHashMap<>(query.parameters());
        if (networked) {
            parameters.put("cacheMb", Integer.toString(settings.resolvedMemoryMb()));
            parameters.put("entityConcurrency", Integer.toString(settings.concurrency()));
        }
        parameters.put("requireComplete", Boolean.toString(settings.requireComplete()));
        return new ProcessPlan(title, description, parameters,
                pipeline.snapshot().stream().map(state -> new ProcessPlan(
                        state.phase().title(), state.phase().description(),
                        Map.of("phaseId", state.phase().id()))).toList());
    }

    @Override public ProcessOutcome<GenerationRun> execute(ProcessContext context) {
        // An operation whose plan is its own single step drives that step from here.
        // One whose plan names the steps it really runs has already had them reported
        // by the work itself, and forcing a step that the plan does not declare would
        // fail on a node that does not exist.
        boolean ownStep = pipeline.snapshot().stream()
                .anyMatch(state -> state.phase().id().equals(phaseId));
        if (ownStep) {
            pipeline.start(phaseId, query.purpose());
        }
        ProcessOutcome<GenerationRun> outcome = context.run(new QuerySubprocess<>(query));
        outcome = RunCompleteness.decide(outcome, settings.requireComplete());
        if (ownStep && outcome.status() == ProcessStatus.SUCCEEDED) {
            pipeline.complete(phaseId, outcome.summary());
        } else if (ownStep && outcome.status() == ProcessStatus.PARTIAL) {
            pipeline.partial(phaseId, outcome.summary());
        } else {
            pipeline.finish(outcome.status(), outcome.summary());
        }
        if (outcome.result() != null && !ownStep) {
            // The plan names the real steps, so each one carries what it accounted for.
            RunPhaseSummaries.record(pipeline, RuleEffects.fromRun(
                    outcome.result().fieldCoverage(),
                    outcome.result().selfReferenceAudit(),
                    outcome.result().ownedCompositionAudit(),
                    outcome.result().kindClassificationAudit(),
                    outcome.result().projectionAudit()));
        } else if (outcome.result() != null) {
            RunPhaseSummaries.recordOperation(pipeline, phaseId, RuleEffects.fromRun(
                    outcome.result().fieldCoverage(),
                    outcome.result().selfReferenceAudit(),
                    outcome.result().ownedCompositionAudit(),
                    outcome.result().kindClassificationAudit(),
                    outcome.result().projectionAudit()));
        }
        return outcome;
    }
}
