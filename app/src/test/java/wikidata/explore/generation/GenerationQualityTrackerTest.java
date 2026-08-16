package wikidata.explore.generation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationQualityTrackerTest {
    @Test void repairedAttemptRemainsInHistoryButNotFinalQuality() {
        GenerationQualityTracker quality = new GenerationQualityTracker();
        quality.failed("Nominee.type", "P31 unavailable", List.of("Q1", "Q2"));
        quality.resolved("Nominee.type", List.of("Q1", "Q2"));

        assertTrue(quality.quality().complete());
        assertFalse(quality.history().isEmpty());
    }

    @Test void onlyExactUnrepairedIdentitiesRemainPartial() {
        GenerationQualityTracker quality = new GenerationQualityTracker();
        quality.failed("kind", "kind evidence unavailable", List.of("Q1", "Q2"));
        quality.resolved("kind", List.of("Q1", "Q9"));

        assertFalse(quality.quality().complete());
        assertTrue(quality.quality().unavailableQids().contains("Q2"));
    }
}
