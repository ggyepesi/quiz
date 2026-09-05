package wikidata.explore.generation;

import objectview.Viewable;
import wikidata.explore.codegen.GeneratedViewableRuntime;

import java.util.List;

/**
 * Map the served pool to typed instances — one mapper, over the pool.
 *
 * <p>Over the POOL and not the extractor's roots, which is what makes a generation
 * preview identical to what a reload serves: a QID stamped as a class root but stored
 * untyped in the pool would otherwise show at generation and vanish on reload.
 *
 * <p>Leaves the graph where it found it. Instances are read from a final graph, not a
 * further stage of one.
 */
public final class MaterializeStep implements PipelineStep {

    @Override public PipelinePhase phase() {
        return PipelinePhase.MATERIALIZE;
    }

    @Override public GraphCheckpoint.Stage requires() {
        return GraphCheckpoint.Stage.FINAL_GRAPH;
    }

    @Override public GraphCheckpoint.Stage produces() {
        return GraphCheckpoint.Stage.FINAL_GRAPH;
    }

    @Override public String execute(PipelineContext context, PipelineState state)
            throws Exception {
        GenerationPipeline pipeline = new GenerationPipeline();
        // The one the flow already built, when it built one. Compiling the model's
        // classes is slow, and a run must carry the runtime that produced its instances
        // rather than a second one that would have.
        GeneratedViewableRuntime runtime = state.runtime() != null ? state.runtime()
                : pipeline.buildRuntime(context.run().request().model());
        List<Viewable> instances = pipeline.materialize(runtime, state.pool());
        state.materialized(runtime, instances);
        return instances.size() + " instance(s) materialized";
    }
}
