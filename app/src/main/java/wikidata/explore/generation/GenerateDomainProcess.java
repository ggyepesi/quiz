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
    private final ProcessPlan plan;
    private final process.ProcessWorkflowPipeline pipeline;

    public GenerateDomainProcess(GeneratedProjectModel project) {
        this(project, GenerateDomainPipeline.configured(project));
    }

    public GenerateDomainProcess(
            GeneratedProjectModel project,
            process.ProcessWorkflowPipeline pipeline) {
        this.project = project.copy();
        this.pipeline = pipeline == null
                ? GenerateDomainPipeline.configured(this.project) : pipeline;
        this.plan = new ProcessPlan(
                "Generate domain",
                "Generate every configured class into one shared domain snapshot",
                Map.of("domain", this.project.name(),
                        "classes", Integer.toString(this.project.classes().size())),
                this.pipeline.snapshot().stream()
                        .map(state -> new ProcessPlan(
                                state.phase().title(), state.phase().description(),
                                Map.of("phaseId", state.phase().id())))
                        .toList());
    }

    @Override public ProcessPlan plan() {
        return plan;
    }

    @Override public ProcessOutcome<GenerationRun> execute(ProcessContext context) {
        ProcessOutcome<GenerationRun> outcome =
                context.run(new QuerySubprocess<>(new GenerateDomainQuery(project, pipeline)));
        GenerationRun run = outcome.result();
        if (outcome.status() == process.ProcessStatus.SUCCEEDED
                && run != null && !run.quality().complete()) {
            return ProcessOutcome.partial(run, null,
                    outcome.summary() + " — partial: "
                            + String.join("; ", run.quality().warnings()));
        }
        return outcome;
    }
}
