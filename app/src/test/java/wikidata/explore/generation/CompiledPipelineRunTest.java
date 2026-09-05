package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 2: one compiled plan decides every phase, from the request alone.
 *
 * <p>No flow name reaches the planner. Each decision follows from something the request
 * states — what the input already is, whether the scope discovers, whether acquisition
 * is permitted — because a {@code generate}-versus-{@code enrich} switch here would
 * re-encode the drift instead of removing it.
 */
class CompiledPipelineRunTest {

    private final GeneratedProjectModel model = GeneratedProjectModel.constellationDemo();

    private static GraphCheckpoint at(GraphCheckpoint.Stage stage) {
        return new GraphCheckpoint(stage, List.of(), List.of(), List.of(),
                datasource.graph.GraphDiscoveryState.EMPTY,
                GenerationRun.Quality.completeQuality(), "sig");
    }

    /**
     * Whether records are constructed follows from the graph, not from the flow.
     *
     * <p>The correction that dissolved the apparent Generate/Enrich inversion: Generate
     * starts from normalized data and must reify; Enrich starts from a graph whose
     * records exist, and reifying again would build a second copy of every one.
     */
    @Test void constructingRecordsIsDecidedByTheInputCheckpoint() {
        assertTrue(CompiledPipelineRun.compile(PipelineRequest.generateDomain(model))
                .runs(PipelinePhase.CONSTRUCT_RECORDS), "from nothing, records are made");

        assertTrue(CompiledPipelineRun.compile(remapFrom(
                        GraphCheckpoint.Stage.NORMALIZED_SOURCE_GRAPH))
                .runs(PipelinePhase.CONSTRUCT_RECORDS),
                "a normalized graph has none yet");

        PhaseDecision alreadyBuilt = CompiledPipelineRun.compile(
                        remapFrom(GraphCheckpoint.Stage.FINAL_GRAPH))
                .decision(PipelinePhase.CONSTRUCT_RECORDS);
        assertEquals(PhaseDecision.Status.SKIP, alreadyBuilt.status());
        assertTrue(alreadyBuilt.reason().contains("constructed before"),
                alreadyBuilt.reason());
    }

    /**
     * Where derived values are refreshed is a dependency, not a preference.
     *
     * <p>Generate refreshes before its semantic and external phases and never again;
     * Enrich refreshes after both. If either acquisition supplies an input to an
     * aggregate, restriction, invert or projection, the two disagree. The answer is the
     * last producer that actually ran, so both converge on it without anyone choosing.
     */
    @Test void derivedValuesAreRefreshedAfterTheirLastProducer() {
        assertEquals(PipelinePhase.ACQUIRE_EXTERNAL_EVIDENCE,
                CompiledPipelineRun.compile(PipelineRequest.generateDomain(model))
                        .refreshDerivedValuesAfter());
        assertEquals(PipelinePhase.ACQUIRE_EXTERNAL_EVIDENCE,
                CompiledPipelineRun.compile(PipelineRequest.enrich(model, at(GraphCheckpoint.Stage.FINAL_GRAPH)))
                        .refreshDerivedValuesAfter(),
                "the same answer, from the same rule");
    }

    /** With nothing fetched, the last producer is the worklist that ran locally. */
    @Test void aRunThatAcquiresNothingRefreshesAfterTheWorklist() {
        assertEquals(PipelinePhase.RESOLVE_SEMANTIC_WORKLIST,
                CompiledPipelineRun.compile(
                                remapFrom(GraphCheckpoint.Stage.FINAL_GRAPH))
                        .refreshDerivedValuesAfter());
    }

    /** Acquisition NONE makes every network phase impossible, each saying so. */
    @Test void aRunForbiddenToAcquireReachesNoNetworkPhase() {
        CompiledPipelineRun remap = CompiledPipelineRun.compile(
                remapFrom(GraphCheckpoint.Stage.NORMALIZED_SOURCE_GRAPH));

        for (PipelinePhase phase : PipelinePhase.values()) {
            if (!phase.network()) continue;
            assertFalse(remap.runs(phase), phase + " reached the network under NONE");
            assertTrue(remap.decision(phase).reason().contains("acquisition forbidden")
                            || remap.decision(phase).reason().contains("existing population"),
                    phase + ": " + remap.decision(phase));
        }
    }

    /** Local semantic work remains required when requests are forbidden. */
    @Test void remapRunsTheLocalSemanticWorklistWithoutCallingItANetworkPhase() {
        CompiledPipelineRun remap = CompiledPipelineRun.compile(
                remapFrom(GraphCheckpoint.Stage.FINAL_GRAPH));

        assertTrue(remap.runs(PipelinePhase.RESOLVE_SEMANTIC_WORKLIST));
        assertFalse(PipelinePhase.RESOLVE_SEMANTIC_WORKLIST.network());
    }

    /** An absent decision is an invalid plan, never implicit permission to run. */
    @Test void aCompiledRunRequiresOneDecisionForEveryPhase() {
        CompiledPipelineRun complete = CompiledPipelineRun.compile(
                PipelineRequest.generateDomain(model));
        java.util.Map<PipelinePhase, PhaseDecision> incomplete =
                new java.util.EnumMap<>(complete.decisions());
        incomplete.remove(PipelinePhase.FINALIZE);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new CompiledPipelineRun(
                        complete.request(), complete.model(), incomplete));
        assertTrue(refused.getMessage().contains("Finalize"), refused.getMessage());
    }

    /** A model that will not compile blocks everything after compiling, with the report. */
    @Test void anUncompilableModelBlocksInsteadOfThrowingLater() {
        GeneratedProjectModel broken = new GeneratedProjectModel();
        GeneratedClassModel alpha = new GeneratedClassModel("Alpha");
        GeneratedClassModel beta = new GeneratedClassModel("Beta");
        alpha.baseClassName("Beta");
        beta.baseClassName("Alpha");
        broken.addClass(alpha);
        broken.addClass(beta);

        CompiledPipelineRun run =
                CompiledPipelineRun.compile(PipelineRequest.generateDomain(broken));

        assertTrue(run.blocked());
        assertTrue(run.decision(PipelinePhase.MATERIALIZE).reason()
                .contains("Class dependency cycle"), run.explain());
    }

    /** A scope naming a class the model lacks is refused before anything is fetched. */
    @Test void aScopeNamingAnUnknownClassIsBlocked() {
        CompiledPipelineRun run = CompiledPipelineRun.compile(
                PipelineRequest.sampleClass(model, "Nonexistent", 8));

        assertEquals(PhaseDecision.Status.BLOCKED,
                run.decision(PipelinePhase.DISCOVER_POPULATION).status());
        assertTrue(run.decision(PipelinePhase.DISCOVER_POPULATION).reason()
                .contains("not a class of this model"), run.explain());
    }

    /** Every non-RUN decision carries a reason; a silent skip reads as an omission. */
    @Test void everySkippedPhaseSaysWhy() {
        for (PipelineRequest request : List.of(
                PipelineRequest.generateDomain(model),
                PipelineRequest.enrich(model, at(GraphCheckpoint.Stage.FINAL_GRAPH)),
                PipelineRequest.sampleClass(model, "Constellation", 8),
                remapFrom(GraphCheckpoint.Stage.FINAL_GRAPH))) {
            CompiledPipelineRun run = CompiledPipelineRun.compile(request);
            run.decisions().forEach((phase, decision) -> {
                if (!decision.runs()) {
                    assertFalse(decision.reason().isBlank(),
                            phase + " under " + request + " was skipped silently");
                }
            });
        }
    }

    /** A decision that runs does not also explain itself away. */
    @Test void aRunningPhaseCarriesNoReason() {
        assertThrows(IllegalArgumentException.class,
                () -> new PhaseDecision(PhaseDecision.Status.RUN, "because"));
        assertThrows(IllegalArgumentException.class,
                () -> new PhaseDecision(PhaseDecision.Status.SKIP, " "));
    }

    /** Sample and Generate decide the same phases; only scope and limits differ. */
    @Test void aSampleAndAFullRunAgreeOnEveryPhase() {
        Map<PipelinePhase, PhaseDecision> full = CompiledPipelineRun
                .compile(PipelineRequest.generateDomain(model)).decisions();
        Map<PipelinePhase, PhaseDecision> sample = CompiledPipelineRun
                .compile(PipelineRequest.sampleClass(model, "Constellation", 8)).decisions();

        assertEquals(full, sample,
                "a bounded run differs by scope and limits, and by nothing else");
    }

    private PipelineRequest remapFrom(GraphCheckpoint.Stage stage) {
        return PipelineRequest.remap(model, at(stage));
    }
}
