package wikidata.explore.generation;

import process.Process;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessPlan;
import process.ProcessStatus;
import process.ProcessWorkflowPipeline;
import process.QuerySubprocess;
import wikidata.explore.query.core.Query;

import java.util.Map;

/** Shared process façade for staged generation operations backed by an existing query. */
public final class GenerationOperationProcess implements Process<GenerationRun> {
    private final String title;
    private final String description;
    private final String phaseId;
    private final Query<GenerationRun> query;
    private final ProcessWorkflowPipeline pipeline;

    public GenerationOperationProcess(
            String title, String description, String phaseId,
            Query<GenerationRun> query, ProcessWorkflowPipeline pipeline) {
        this.title = title;
        this.description = description;
        this.phaseId = phaseId;
        this.query = query;
        this.pipeline = pipeline;
    }

    @Override public ProcessPlan plan() {
        return new ProcessPlan(title, description, query.parameters(),
                pipeline.snapshot().stream().map(state -> new ProcessPlan(
                        state.phase().title(), state.phase().description(),
                        Map.of("phaseId", state.phase().id()))).toList());
    }

    @Override public ProcessOutcome<GenerationRun> execute(ProcessContext context) {
        pipeline.start(phaseId, query.purpose());
        ProcessOutcome<GenerationRun> outcome = context.run(new QuerySubprocess<>(query));
        if (outcome.status() == ProcessStatus.SUCCEEDED) {
            pipeline.complete(phaseId, outcome.summary());
        } else if (outcome.status() == ProcessStatus.PARTIAL) {
            pipeline.partial(phaseId, outcome.summary());
        } else {
            pipeline.finish(outcome.status(), outcome.summary());
        }
        return outcome;
    }
}
