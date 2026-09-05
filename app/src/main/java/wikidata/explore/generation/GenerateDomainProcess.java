package wikidata.explore.generation;

import process.Process;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessPlan;
import process.QuerySubprocess;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.logical.GenerateDomainQuery;

import java.util.Map;

/** Process façade for domain generation; the established Query is an explicit subprocess. */
public final class GenerateDomainProcess implements Process<GenerationRun> {
    private final GeneratedProjectModel project;
    private final CompiledPipelineRun compiledRun;
    private final process.ProcessWorkflowPipeline pipeline;
    private final GenerationExecutionSettings settings;

    public GenerateDomainProcess(GeneratedProjectModel project) {
        this(CompiledPipelineRun.compile(PipelineRequest.generateDomain(project)),
                null, new GenerationExecutionSettings());
    }

    public GenerateDomainProcess(
            CompiledPipelineRun compiledRun,
            process.ProcessWorkflowPipeline pipeline,
            GenerationExecutionSettings settings) {
        if (compiledRun == null) throw new IllegalArgumentException("No compiled pipeline run");
        this.compiledRun = compiledRun;
        this.project = compiledRun.request().model();
        this.settings = settings == null ? new GenerationExecutionSettings() : settings;
        this.pipeline = pipeline == null
                ? GenerateDomainPipeline.configured(compiledRun) : pipeline;
    }

    @Override public ProcessPlan plan() {
        return new ProcessPlan(
                "Generate domain",
                "Generate every configured class into one shared domain snapshot",
                Map.of("domain", this.project.name(),
                        "classes", Integer.toString(this.project.classes().size()),
                        "cacheMb", Integer.toString(this.settings.resolvedMemoryMb()),
                        "entityConcurrency", Integer.toString(this.settings.concurrency()),
                        "requireComplete", Boolean.toString(this.settings.requireComplete())),
                pipeline.snapshot().stream()
                        .map(state -> new ProcessPlan(
                                state.phase().title(), state.phase().description(),
                                Map.of("phaseId", state.phase().id())))
                        .toList());
    }

    @Override public ProcessOutcome<GenerationRun> execute(ProcessContext context) {
        ProcessOutcome<GenerationRun> outcome =
                context.run(new QuerySubprocess<>(new GenerateDomainQuery(
                        compiledRun, pipeline, settings)));
        outcome = RunCompleteness.decide(outcome, settings.requireComplete());
        if (outcome.result() != null) {
            RunPhaseSummaries.record(pipeline, RuleEffects.fromRun(
                    outcome.result().fieldCoverage(),
                    outcome.result().selfReferenceAudit(),
                    outcome.result().ownedCompositionAudit(),
                    outcome.result().kindClassificationAudit(),
                    outcome.result().projectionAudit()));
        }
        return outcome;
    }
}
