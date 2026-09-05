package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tail is routed in two calls, and the order still cannot be got wrong.
 *
 * <p>Generate has about 120 lines of reporting between finalization and materialization,
 * and moving them would change what a reader sees mid-run. So the executor is called
 * twice over ONE state — and the guarantee does not come from a single call: materialize
 * requires a FINAL_GRAPH and only finalize leaves one, so a flow that called them the
 * wrong way round would be refused rather than quietly mapping an unfinalized graph.
 */
class RoutedTailKeepsItsOrderTest {

    private final GeneratedProjectModel model = GeneratedProjectModel.constellationDemo();

    private PipelineContext context() {
        return new PipelineContext(CompiledPipelineRun.compile(
                PipelineRequest.generateDomain(model)), null, null, null);
    }

    private static WikidataDynamicObject star(String qid) {
        WikidataDynamicObject object = new WikidataDynamicObject(qid, qid);
        object.type("Constellation");
        return object;
    }

    /** Materializing before finalizing is refused across calls, not just within one. */
    @Test void materializingAnUnfinalizedGraphIsRefused() {
        PipelineState state = PipelineState.over(
                GraphCheckpoint.Stage.CONSTRUCTED_GRAPH,
                new ArrayList<>(List.of(star("Q1"))));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new PipelineExecutor().with(new MaterializeStep())
                        .run(context(), state));

        assertTrue(refused.getMessage().contains("needs a FINAL_GRAPH"),
                refused.getMessage());
    }

    /**
     * The state shares the caller's graph, because finalization prunes it.
     *
     * <p>A copy would prune the copy and leave the flow holding what was removed — a
     * routed step that silently stopped pruning, which no test of the step alone would
     * have caught.
     */
    @Test void thePoolTheFlowHoldsIsThePoolTheStepPrunes() {
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(star("Q1")));
        PipelineState state =
                PipelineState.over(GraphCheckpoint.Stage.CONSTRUCTED_GRAPH, pool);

        state.pool().add(star("Q2"));

        assertEquals(2, pool.size(), "the state IS the run's graph, not a copy of it");
        assertSame(pool, state.pool());
    }

    /** A state built from a checkpoint copies, because a checkpoint is a record. */
    @Test void aStateFromACheckpointDoesNotWriteBackIntoIt() {
        GraphCheckpoint checkpoint = new GraphCheckpoint(
                GraphCheckpoint.Stage.FINAL_GRAPH, List.of(star("Q1")), List.of(),
                datasource.graph.GraphDiscoveryState.EMPTY,
                GenerationRun.Quality.completeQuality(), "sig");

        PipelineState.from(checkpoint).pool().add(star("Q2"));

        assertEquals(1, checkpoint.objects().size(),
                "a checkpoint records what was; a run does not edit its own history");
    }

    /** Once finalize has run, materialize is allowed — the same state carries the stage. */
    @Test void finalizingFirstLetsTheSecondCallProceed() throws Exception {
        PipelineState state = PipelineState.over(
                GraphCheckpoint.Stage.CONSTRUCTED_GRAPH,
                new ArrayList<>(List.of(star("Q1"))));
        state.reached(GraphCheckpoint.Stage.FINAL_GRAPH);

        new PipelineExecutor().with(new MaterializeStep()).run(context(), state);

        assertNotNull(state.runtime());
    }
}
