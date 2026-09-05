package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Network permission belongs to the acquisition, not to the phase that may perform one.
 *
 * <p>A boolean on the step was wrong, and wrongly enough to break Remap. The semantic
 * worklist always has local work — stamping roles, classifying kinds from stored
 * evidence, composing owned parts — and only MAY acquire on top of it. Refusing the phase
 * under acquisition NONE refuses the local work too, which is how a flow ends up reaching
 * past the worklist for OwnedComponents alone and skipping the two steps composition
 * depends on.
 */
class NetworkPermissionIsPerOperationTest {

    private final GeneratedProjectModel model = GeneratedProjectModel.constellationDemo();

    private static GraphCheckpoint constructed() {
        return GraphCheckpoint.constructed(List.of(), List.of(), List.of(),
                datasource.graph.GraphDiscoveryState.EMPTY,
                GenerationRun.Quality.completeQuality(), "sig");
    }

    private PipelineContext forbidden() {
        return new PipelineContext(CompiledPipelineRun.compile(
                PipelineRequest.remap(model, constructed())), null, null, null);
    }

    /** The step that made the boolean wrong says what it actually needs. */
    @Test void theSemanticWorklistMayAcquireRatherThanMustAcquire() {
        assertEquals(PipelineStep.NetworkUse.OPTIONAL,
                new SemanticWorklistStep().networkUse());
        assertEquals(PipelineStep.NetworkUse.REQUIRED,
                ConstructRecordsStep.acquiring(records -> java.util.Map.of()).networkUse());
        assertEquals(PipelineStep.NetworkUse.NONE,
                ConstructRecordsStep.replaying(java.util.Map.of()).networkUse());
    }

    /** A purely local step needs no permission at all. */
    @Test void aLocalStepNeedsNoPermission() {
        assertEquals(PipelineStep.NetworkUse.NONE, new FinalizeStep().networkUse());
        assertEquals(PipelineStep.NetworkUse.NONE, new MaterializeStep().networkUse());
    }

    /**
     * A forbidden run still converges — it does the local subset.
     *
     * <p>The subset is chosen by what the context HAS: no client, so the acquiring half
     * is unreachable rather than skipped by agreement.
     */
    @Test void aForbiddenRunStillResolvesTheWorklistLocally() throws Exception {
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(star()));
        PipelineState state =
                PipelineState.over(GraphCheckpoint.Stage.CONSTRUCTED_GRAPH, pool);

        PipelineExecutor.Outcome outcome = new PipelineExecutor()
                .with(new SemanticWorklistStep()).run(forbidden(), state);

        assertTrue(outcome.toString().contains("local only"), outcome.toString());
        assertEquals(GraphCheckpoint.Stage.CONSTRUCTED_GRAPH, state.stage(),
                "the worklist settles a graph, it does not advance its stage");
    }

    /** A step that cannot work without acquiring is refused under NONE. */
    @Test void aStepThatMustAcquireIsRefused() {
        PipelineStep mustAcquire = new PipelineStep() {
            @Override public PipelinePhase phase() {
                return PipelinePhase.ACQUIRE_EXTERNAL_EVIDENCE;
            }
            @Override public GraphCheckpoint.Stage requires() {
                return GraphCheckpoint.Stage.NORMALIZED_SOURCE_GRAPH;
            }
            @Override public GraphCheckpoint.Stage produces() {
                return GraphCheckpoint.Stage.CONSTRUCTED_GRAPH;
            }
            @Override public String execute(PipelineContext c, PipelineState s) {
                return fail("it should never have been run");
            }
        };
        assertEquals(PipelineStep.NetworkUse.REQUIRED, mustAcquire.networkUse(),
                "a phase that is only an acquisition defaults to needing one");

        CompiledPipelineRun run = CompiledPipelineRun.compile(
                PipelineRequest.remap(model, constructed()));
        var decisions = new java.util.EnumMap<>(run.decisions());
        decisions.put(PipelinePhase.ACQUIRE_EXTERNAL_EVIDENCE, PhaseDecision.run());

        assertThrows(IllegalStateException.class, () -> new PipelineExecutor()
                .with(mustAcquire)
                .run(new PipelineContext(new CompiledPipelineRun(
                                run.request(), run.model(), decisions), null, null, null),
                        PipelineState.over(GraphCheckpoint.Stage.CONSTRUCTED_GRAPH,
                                new ArrayList<>())));
    }

    /** The real acquiring construction cannot hide behind OPTIONAL under Remap. */
    @Test void acquiringCompanionSetsAreRefusedBeforeTheirFunctionRuns() {
        boolean[] called = { false };
        PipelineContext localReconstruction = new PipelineContext(
                CompiledPipelineRun.compile(PipelineRequest.remap(model,
                        GraphCheckpoint.normalized(List.of(), List.of(),
                                datasource.graph.GraphDiscoveryState.EMPTY,
                                GenerationRun.Quality.completeQuality(), "sig"))),
                null, null, null);

        assertThrows(IllegalStateException.class, () -> new PipelineExecutor()
                .with(ConstructRecordsStep.acquiring(records -> {
                    called[0] = true;
                    return java.util.Map.of();
                }))
                .run(localReconstruction, PipelineState.over(
                        GraphCheckpoint.Stage.NORMALIZED_SOURCE_GRAPH,
                        new ArrayList<>())));

        assertFalse(called[0], "network-capable work was refused before invocation");
    }

    /** Constructing is decided by the graph; a constructed one is not constructed twice. */
    @Test void aConstructedGraphIsNotConstructedAgain() throws Exception {
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(star()));

        PipelineExecutor.Outcome outcome = new PipelineExecutor()
                .with(ConstructRecordsStep.replaying(java.util.Map.of()))
                .run(forbidden(),
                        PipelineState.over(GraphCheckpoint.Stage.CONSTRUCTED_GRAPH, pool));

        assertTrue(outcome.toString().contains("constructed before"), outcome.toString());
    }

    private static WikidataDynamicObject star() {
        WikidataDynamicObject object = new WikidataDynamicObject("Q1", "Sirius");
        object.type("Constellation");
        return object;
    }
}
