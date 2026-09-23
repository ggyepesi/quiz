package wikidata.explore.query.logical;

import objectview.Viewable;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.generation.CompiledPipelineRun;
import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.GenerationRecoveryStore;
import wikidata.explore.generation.GenerationRun;
import wikidata.explore.generation.GraphCheckpoint;
import wikidata.explore.generation.MaterializeStep;
import wikidata.explore.generation.PipelineContext;
import wikidata.explore.generation.PipelineExecutor;
import wikidata.explore.generation.PipelineState;
import wikidata.explore.model.GeneratedProjectModel;
import work.Query;
import work.QueryContext;

import java.util.List;
import java.util.Map;

/** Maps a persisted final graph without repeating acquisition or transformation. */
public final class RecoverFinalGraphQuery implements Query<GenerationRun> {
    private final CompiledPipelineRun compiledRun;
    private final GenerationRecoveryStore recovery;

    public RecoverFinalGraphQuery(
            CompiledPipelineRun compiledRun, GenerationRecoveryStore recovery) {
        this.compiledRun = java.util.Objects.requireNonNull(compiledRun, "No compiled run");
        this.recovery = java.util.Objects.requireNonNull(recovery, "No recovery store");
    }

    @Override public String purpose() {
        return "Resume materialization";
    }

    @Override public String skeleton() {
        return "load finalized recovery graph -> materialize instances";
    }

    @Override public Map<String, String> parameters() {
        return Map.of("file", recovery.snapshotFile().getPath());
    }

    @Override public GenerationRun execute(QueryContext context) throws Exception {
        GeneratedProjectModel model = compiledRun.request().model();
        return context.step("Resume materialization from "
                        + recovery.snapshotFile().getPath(), "Local file", null,
                parameters(), step -> {
                    GenerationRecoveryStore.Recovered recovered = recovery.load(model);
                    var saved = recovered.snapshot();
                    GraphCheckpoint checkpoint = GraphCheckpoint.finalGraph(
                            saved.objects(), saved.loadedDeclarations(),
                            saved.graphDiscovery(), recovered.quality(),
                            wikidata.explore.generation.DomainSave.signature(model));
                    PipelineState state = PipelineState.from(checkpoint);
                    PipelineContext pipelineContext = new PipelineContext(
                            compiledRun, null,
                            wikidata.explore.extract.GenerationLog.NOOP,
                            context.cancellation());
                    new PipelineExecutor().with(new MaterializeStep())
                            .run(pipelineContext, state);
                    GeneratedViewableRuntime runtime = state.runtime();
                    List<Viewable> instances = state.instances();
                    recovery.clear();
                    step.summary("Loaded " + state.pool().size() + " finalized objects from "
                            + recovery.snapshotFile().getPath() + "; materialized "
                            + instances.size() + " instances; removed the recovery files.");
                    return new GenerationRun(
                            model, 0, new GenerationPipeline().plan(model), state.pool(),
                            runtime, instances, null, saved.loadedDeclarations(),
                            recovered.quality(), List.of(),
                            GenerationRun.SelfReferenceAudit.restored(saved.selfReferences()),
                            GenerationRun.OwnedCompositionAudit.notRun(),
                            GenerationRun.KindClassificationAudit.notRun(),
                            GenerationRun.ProjectionAudit.notRun());
                });
    }

    @Override public int rowCount(GenerationRun result) {
        return result == null ? 0 : result.size();
    }

    @Override public String summary(GenerationRun result) {
        return rowCount(result) + " instances";
    }
}
