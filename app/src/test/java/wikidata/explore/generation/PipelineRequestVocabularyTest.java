package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedProjectModel;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 1 of {@code docs/parameterized-generation-pipeline.md}: the five flows said
 * as combinations of four decisions.
 *
 * <p>Nothing executes yet. What this fixes is the vocabulary — and the one claim the
 * whole design rests on: a bounded run differs from a full one by scope and limits, and
 * by nothing else. Every time a flow has instead dropped a phase, the result was not a
 * smaller answer but a different one.
 */
class PipelineRequestVocabularyTest {

    private final GeneratedProjectModel model = GeneratedProjectModel.constellationDemo();

    /** The invariant, stated where it can be checked. */
    @Test void aSampleDiffersFromAFullRunByScopeAndLimitsAlone() {
        PipelineRequest full = PipelineRequest.generateDomain(model);
        PipelineRequest sample = PipelineRequest.sampleClass(model, "Star", 8);

        assertEquals(full.input(), sample.input());
        assertEquals(full.acquisition(), sample.acquisition());
        assertNotEquals(full.scope(), sample.scope());
        assertNotEquals(full.limits(), sample.limits());
    }

    /** A preview and a sample are the same request with a different number. */
    @Test void aSampleIsAPreviewBoundedHarder() {
        PipelineRequest preview = PipelineRequest.generateClassPreview(model, "Star", 1);
        PipelineRequest sample = PipelineRequest.sampleClass(model, "Star", 8);

        assertEquals(preview.scope(), sample.scope());
        assertEquals(preview.acquisition(), sample.acquisition());
        assertEquals(preview.output(), sample.output());
    }

    /** Remap reaches no network — the design's one hard invariant. */
    @Test void remapMayNotAcquire() {
        PipelineRequest remap = PipelineRequest.remap(model, normalized());

        assertFalse(remap.mayAcquire());
        assertEquals(PipelineRequest.Acquisition.NONE, remap.acquisition());
    }

    /** Enrich asks only for what its graph does not answer. */
    @Test void enrichAsksOnlyForWhatIsMissing() {
        GraphCheckpoint saved = finalGraph();
        PipelineRequest enrich = PipelineRequest.enrich(model, saved);

        assertEquals(PipelineRequest.Acquisition.MISSING_ONLY, enrich.acquisition());
        assertSame(saved, enrich.input().suppliedCheckpoint().orElseThrow());
        assertFalse(enrich.scope().discovers(), "it works on what it already has");
    }

    /** Nothing applies itself; an interactive run is inspected first. */
    @Test void aPreviewIsNeverAReplacement() {
        assertEquals(PipelineRequest.Output.PREVIEW,
                PipelineRequest.sampleClass(model, "Star", 8).output());
        assertEquals(PipelineRequest.Output.PREVIEW,
                PipelineRequest.generateClassPreview(model, "Star", 1).output());
        assertEquals(PipelineRequest.Output.REPLACEMENT_CANDIDATE,
                PipelineRequest.generateDomain(model).output());
    }

    /** A request that cannot be answered is refused where it is made. */
    @Test void aRunFromNothingThatMayNotAcquireIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PipelineRequest(
                model, PipelineInput.empty(), PipelineScope.wholeDomain(),
                PipelineRequest.Acquisition.NONE, PipelineLimits.asConfigured(),
                PipelineRequest.Output.PREVIEW));
    }

    /** Only a class scope names a class; the state where two compete is unreachable. */
    @Test void aScopeIsOneWayOfBeingScoped() {
        assertThrows(IllegalArgumentException.class,
                () -> new PipelineScope(PipelineScope.Kind.WHOLE_DOMAIN, "Star"));
        assertThrows(IllegalArgumentException.class,
                () -> new PipelineScope(PipelineScope.Kind.CLASS_PRODUCTION_CHAIN, " "));
    }

    /** "Unbounded" means the model's own limits, not the absence of limits. */
    @Test void anUnboundedRunStillObeysTheModel() {
        assertFalse(PipelineLimits.asConfigured().bounded());
        assertTrue(PipelineLimits.members(8).bounded());
        assertEquals("as configured", PipelineLimits.asConfigured().toString());
    }

    @Test void zeroDepthMeansFollowNoChildEdges() {
        PipelineRequest preview = PipelineRequest.generateClassPreview(model, "Star", 0);

        assertTrue(preview.limits().bounded());
        assertEquals(0, preview.limits().depth());
    }

    @Test void invalidNegativeLimitsAreRefusedRatherThanReinterpreted() {
        assertThrows(IllegalArgumentException.class, () -> new PipelineLimits(-2, 1));
        assertThrows(IllegalArgumentException.class, () -> new PipelineLimits(1, -2));
    }

    @Test void theCheckpointIsTheOnlyOwnerOfInputStage() {
        GraphCheckpoint normalized = normalized();
        PipelineRequest request = PipelineRequest.remap(model, normalized);

        assertSame(normalized, request.input().suppliedCheckpoint().orElseThrow());
        assertEquals(GraphCheckpoint.Stage.NORMALIZED_SOURCE_GRAPH,
                request.input().suppliedCheckpoint().orElseThrow().stage());
    }

    private static GraphCheckpoint normalized() {
        return GraphCheckpoint.normalized(java.util.List.of(), java.util.List.of(),
                datasource.graph.GraphDiscoveryState.EMPTY,
                GenerationRun.Quality.completeQuality(), "sig");
    }

    private static GraphCheckpoint finalGraph() {
        return GraphCheckpoint.finalGraph(java.util.List.of(), java.util.List.of(),
                datasource.graph.GraphDiscoveryState.EMPTY,
                GenerationRun.Quality.completeQuality(), "sig");
    }
}
