package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 3: the executor owns the order, and refuses before it runs.
 *
 * <p>Five flows each deciding the order produced five orderings that drifted, and the
 * drift was invisible because a hand-authored sequence looks correct from inside the
 * method that wrote it. The refusals are what make the difference: a step whose graph is
 * not ready, or that would reach the network under a run forbidden to acquire, stops
 * before doing anything rather than part-way through.
 */
class PipelineExecutorTest {

    private final GeneratedProjectModel model = GeneratedProjectModel.constellationDemo();

    /** A step that records that it ran, without doing anything real. */
    private static final class Recording implements PipelineStep {
        private final PipelinePhase phase;
        private final GraphCheckpoint.Stage requires;
        private final GraphCheckpoint.Stage produces;
        private final List<String> ran;

        Recording(PipelinePhase phase, GraphCheckpoint.Stage requires,
                GraphCheckpoint.Stage produces, List<String> ran) {
            this.phase = phase;
            this.requires = requires;
            this.produces = produces;
            this.ran = ran;
        }

        @Override public PipelinePhase phase() { return phase; }
        @Override public GraphCheckpoint.Stage requires() { return requires; }
        @Override public GraphCheckpoint.Stage produces() { return produces; }
        @Override public String execute(PipelineContext context, PipelineState state) {
            ran.add(phase.name());
            return "done";
        }
    }

    private PipelineContext contextFor(PipelineRequest request) {
        return new PipelineContext(CompiledPipelineRun.compile(request), null, null, null);
    }

    private static GraphCheckpoint checkpoint(GraphCheckpoint.Stage stage) {
        return new GraphCheckpoint(stage, List.of(), List.of(),
                datasource.graph.GraphDiscoveryState.EMPTY,
                GenerationRun.Quality.completeQuality(), "sig");
    }

    /** Phases run in the vocabulary's order, whichever order they were registered in. */
    @Test void theOrderIsThePhasesAndNotTheRegistration() throws Exception {
        List<String> ran = new ArrayList<>();
        PipelineState state = PipelineState.from(
                checkpoint(GraphCheckpoint.Stage.CONSTRUCTED_GRAPH));

        new PipelineExecutor()
                .with(new Recording(PipelinePhase.MATERIALIZE,
                        GraphCheckpoint.Stage.FINAL_GRAPH,
                        GraphCheckpoint.Stage.FINAL_GRAPH, ran))
                .with(new Recording(PipelinePhase.FINALIZE,
                        GraphCheckpoint.Stage.CONSTRUCTED_GRAPH,
                        GraphCheckpoint.Stage.FINAL_GRAPH, ran))
                .run(contextFor(PipelineRequest.remap(model,
                        checkpoint(GraphCheckpoint.Stage.CONSTRUCTED_GRAPH))), state);

        assertEquals(List.of("FINALIZE", "MATERIALIZE"), ran);
    }

    /** A phase the plan skipped is not run, and the plan's reason is what is reported. */
    @Test void aSkippedPhaseIsReportedWithThePlansReason() throws Exception {
        List<String> ran = new ArrayList<>();
        PipelineExecutor.Outcome outcome = new PipelineExecutor()
                .with(new Recording(PipelinePhase.CONSTRUCT_RECORDS,
                        GraphCheckpoint.Stage.NORMALIZED_SOURCE_GRAPH,
                        GraphCheckpoint.Stage.CONSTRUCTED_GRAPH, ran))
                .run(contextFor(PipelineRequest.remap(model,
                                checkpoint(GraphCheckpoint.Stage.FINAL_GRAPH))),
                        PipelineState.from(checkpoint(GraphCheckpoint.Stage.FINAL_GRAPH)));

        assertTrue(ran.isEmpty(), "records were constructed before this checkpoint");
        assertTrue(outcome.toString().contains("constructed before"), outcome.toString());
    }

    /** A step whose graph is not ready refuses, rather than acting on what it cannot. */
    @Test void aStepRefusesAGraphThatHasNotReachedItsStage() {
        PipelineState normalized =
                PipelineState.from(checkpoint(GraphCheckpoint.Stage.NORMALIZED_SOURCE_GRAPH));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new PipelineExecutor()
                        .with(new MaterializeStep())
                        .run(contextFor(PipelineRequest.remap(model,
                                checkpoint(GraphCheckpoint.Stage.NORMALIZED_SOURCE_GRAPH))),
                                normalized));

        assertTrue(refused.getMessage().contains("needs a FINAL_GRAPH"),
                refused.getMessage());
    }

    /**
     * A run forbidden to acquire cannot reach the network, twice over.
     *
     * <p>The planner already skips those phases. The executor refuses them anyway,
     * because "no network under NONE" is the invariant the whole Remap flow rests on and
     * one lock on it is one mistake away from being none.
     */
    @Test void aStepThatWouldAcquireIsRefusedUnderAcquisitionNone() {
        List<String> ran = new ArrayList<>();
        CompiledPipelineRun forbidden = CompiledPipelineRun.compile(
                PipelineRequest.remap(model, checkpoint(GraphCheckpoint.Stage.FINAL_GRAPH)));
        // A plan that wrongly says RUN for a network phase is the case this guards.
        var decisions = new java.util.LinkedHashMap<>(forbidden.decisions());
        decisions.put(PipelinePhase.ACQUIRE_EXTERNAL_EVIDENCE, PhaseDecision.run());
        CompiledPipelineRun tampered = new CompiledPipelineRun(
                forbidden.request(), forbidden.model(), decisions);

        assertThrows(IllegalStateException.class, () -> new PipelineExecutor()
                .with(new Recording(PipelinePhase.ACQUIRE_EXTERNAL_EVIDENCE,
                        GraphCheckpoint.Stage.NORMALIZED_SOURCE_GRAPH,
                        GraphCheckpoint.Stage.FINAL_GRAPH, ran))
                .run(new PipelineContext(tampered, null, null, null),
                        PipelineState.from(checkpoint(GraphCheckpoint.Stage.FINAL_GRAPH))));
        assertTrue(ran.isEmpty(), "refused before it ran, not part-way through");
    }

    /** A context for a run that may not acquire is not even given a client. */
    @Test void aForbiddenRunIsGivenNothingToAcquireWith() {
        assertThrows(IllegalArgumentException.class, () -> new PipelineContext(
                CompiledPipelineRun.compile(PipelineRequest.remap(model,
                        checkpoint(GraphCheckpoint.Stage.FINAL_GRAPH))),
                new wikidata.api.WikidataApiClient("test"), null, null));
    }

    /** A blocked plan runs nothing at all, and says what is wrong with it. */
    @Test void aBlockedRunExecutesNothing() {
        wikidata.explore.model.GeneratedClassModel alpha =
                new wikidata.explore.model.GeneratedClassModel("Alpha");
        alpha.baseClassName("Alpha");
        GeneratedProjectModel broken = new GeneratedProjectModel();
        broken.addClass(alpha);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new PipelineExecutor().with(new MaterializeStep()).run(
                        contextFor(PipelineRequest.generateDomain(broken)),
                        PipelineState.empty()));

        assertTrue(refused.getMessage().contains("blocked"), refused.getMessage());
    }

    /** A graph reaches a stage; it does not go back to an earlier one. */
    @Test void aStageIsReachedAndNotSet() {
        PipelineState state = new PipelineState(
                GraphCheckpoint.Stage.FINAL_GRAPH, List.of(star()));

        assertThrows(IllegalStateException.class,
                () -> state.reached(GraphCheckpoint.Stage.NORMALIZED_SOURCE_GRAPH));
    }

    /** Materializing leaves the graph where it found it: instances are read, not a stage. */
    @Test void materializingDoesNotAdvanceTheGraph() throws Exception {
        PipelineState state = new PipelineState(
                GraphCheckpoint.Stage.FINAL_GRAPH, List.of(star()));

        new PipelineExecutor().with(new MaterializeStep()).run(
                contextFor(PipelineRequest.remap(model,
                        checkpoint(GraphCheckpoint.Stage.FINAL_GRAPH))), state);

        assertEquals(GraphCheckpoint.Stage.FINAL_GRAPH, state.stage());
        assertNotNull(state.runtime(), "a runtime was built to map through");
    }

    private static WikidataDynamicObject star() {
        WikidataDynamicObject object = new WikidataDynamicObject("Q1", "Sirius");
        object.type("Constellation");
        return object;
    }
}
