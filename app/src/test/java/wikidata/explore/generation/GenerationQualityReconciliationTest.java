package wikidata.explore.generation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationQualityReconciliationTest {

    @Test void repairedAcquisitionFailureDoesNotKeepEnrichPartial() {
        GenerationRun.Quality before = GenerationRun.Quality.partial(
                List.of("Entity-kind evidence unavailable (2 unresolved)"),
                List.of("Q1", "Q2"));

        assertTrue(GenerationPipeline.reconcileQuality(
                before, GenerationRun.Quality.completeQuality()).complete());
    }

    @Test void extractionFailureSurvivesBecauseEnrichDoesNotRepeatExtraction() {
        GenerationRun.Quality before = GenerationRun.Quality.partial(
                List.of("1 child extraction query(ies) did not complete"), List.of());

        GenerationRun.Quality after = GenerationPipeline.reconcileQuality(
                before, GenerationRun.Quality.completeQuality());

        assertTrue(!after.complete());
        assertEquals(before.warnings(), after.warnings());
    }
}
