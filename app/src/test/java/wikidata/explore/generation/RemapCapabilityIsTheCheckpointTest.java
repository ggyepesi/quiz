package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a Remap can do follows from the graph it starts from, and says so.
 *
 * <p>It was implicit in whether a field happened to be null: with the enriched pool still
 * in memory a Remap rebuilt everything, and after a restart it quietly did less. The
 * capability is now the stage of the checkpoint — derived, so there is no second answer
 * to keep in step.
 */
class RemapCapabilityIsTheCheckpointTest {

    private static WikidataDynamicObject star() {
        WikidataDynamicObject object = new WikidataDynamicObject("Q1", "Sirius");
        object.type("Star");
        return object;
    }

    @Test void aNormalizedGraphCanBeRebuiltInFull() {
        GraphCheckpoint checkpoint = GraphCheckpoint.normalized(
                List.of(star()), List.of(), null, "sig");

        assertEquals(GraphCheckpoint.RemapCapability.FULL_RECONSTRUCTION,
                checkpoint.remapCapability());
    }

    /** Reify over an already-reified graph would build a second copy of every record. */
    @Test void aFinalGraphAllowsOnlyIdempotentTransforms() {
        GraphCheckpoint checkpoint = GraphCheckpoint.finalGraph(
                List.of(star()), List.of(), null, "sig");

        assertEquals(GraphCheckpoint.RemapCapability.IDEMPOTENT_ONLY,
                checkpoint.remapCapability());
    }

    /** A run holds a settled graph, whatever it took to get there. */
    @Test void aRunIsAFinalGraph() {
        assertEquals(GraphCheckpoint.Stage.FINAL_GRAPH, run(null).checkpoint().stage());
    }

    /** With the enriched pool still in memory, a Remap of that run can do everything. */
    @Test void aRunKeepingItsEnrichedPoolCanStillBeRebuiltInFull() {
        GenerationRun kept = run(new GenerationRun.RemapState(
                List.of(star()), Map.of()));

        assertEquals(GraphCheckpoint.Stage.NORMALIZED_SOURCE_GRAPH,
                kept.remapCheckpoint().stage());
        assertEquals(GraphCheckpoint.RemapCapability.FULL_RECONSTRUCTION,
                kept.remapCheckpoint().remapCapability());
    }

    /** After a restart there is only the final graph, and the capability drops. */
    @Test void aRunWithoutItFallsBackToTheFinalGraph() {
        GenerationRun restored = run(null);

        assertEquals(GraphCheckpoint.RemapCapability.IDEMPOTENT_ONLY,
                restored.remapCheckpoint().remapCapability());
    }

    /** An empty pool is not a normalized graph; it is no graph. */
    @Test void anEmptyRemapStateIsNotACheckpoint() {
        GenerationRun empty = run(new GenerationRun.RemapState(List.of(), Map.of()));

        assertEquals(GraphCheckpoint.Stage.FINAL_GRAPH,
                empty.remapCheckpoint().stage());
    }

    /** A checkpoint says which model made it, and makes no claim when it cannot. */
    @Test void anUnknownSignatureClaimsNothing() {
        GraphCheckpoint unsigned = GraphCheckpoint.finalGraph(
                List.of(star()), List.of(), null, "");

        assertFalse(unsigned.producedBy("sig"));
        assertFalse(GraphCheckpoint.finalGraph(List.of(), List.of(), null, "sig")
                .producedBy(null));
        assertTrue(GraphCheckpoint.finalGraph(List.of(), List.of(), null, "sig")
                .producedBy("sig"));
    }

    private static GenerationRun run(GenerationRun.RemapState remapState) {
        return new GenerationRun(
                wikidata.explore.model.GeneratedProjectModel.constellationDemo(), 0, null,
                List.of(star()), null, List.of(), remapState, List.of());
    }
}
