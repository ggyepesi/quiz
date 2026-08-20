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
    private final process.ProcessWorkflowPipeline pipeline;
    private final GenerationExecutionSettings settings;

    public GenerateDomainProcess(GeneratedProjectModel project) {
        this(project, GenerateDomainPipeline.configured(project));
    }

    public GenerateDomainProcess(
            GeneratedProjectModel project,
            process.ProcessWorkflowPipeline pipeline) {
        this(project, pipeline, new GenerationExecutionSettings());
    }

    public GenerateDomainProcess(
            GeneratedProjectModel project,
            process.ProcessWorkflowPipeline pipeline,
            GenerationExecutionSettings settings) {
        this.project = project.copy();
        this.settings = settings == null ? new GenerationExecutionSettings() : settings;
        this.pipeline = pipeline == null
                ? GenerateDomainPipeline.configured(this.project) : pipeline;
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
                        project, pipeline, settings)));
        return RunCompleteness.decide(outcome, settings.requireComplete());
    }
}
