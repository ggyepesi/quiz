package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import process.ProcessOutcome;
import process.ProcessStatus;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationExecutionSettingsTest {
    @Test
    void profilesResolveToBoundedRunResources() {
        GenerationExecutionSettings settings = new GenerationExecutionSettings(false);
        settings.memoryProfile(GenerationExecutionSettings.MemoryProfile.CUSTOM);
        settings.customMemoryMb(960);
        settings.networkProfile(GenerationExecutionSettings.NetworkProfile.GENTLE);

        assertEquals(960, settings.resolvedMemoryMb());
        assertEquals(2, settings.concurrency());
        assertEquals(960L * 1024L * 1024L, settings.newFactStore().maxEstimatedBytes());
    }

    /**
     * The policy decides what an incomplete run MEANS, never whether it survives. A
     * failed outcome carries no result, so refusing incompleteness that way threw away
     * the twenty-five minutes of data the run had already produced — the opposite of
     * "what the reachable batches answered is real data".
     */
    @Test void anIncompleteRunIsKeptWhicheverWayThePolicyIsSet() {
        GenerationRun incomplete = runWithWarning();

        ProcessOutcome<GenerationRun> refused = outcomeFor(incomplete, true);
        assertEquals(ProcessStatus.PARTIAL, refused.status());
        assertSame(incomplete, refused.result(),
                "refusing to accept it automatically is not discarding it");

        ProcessOutcome<GenerationRun> tolerated = outcomeFor(incomplete, false);
        assertEquals(ProcessStatus.SUCCEEDED, tolerated.status());
        assertSame(incomplete, tolerated.result());
        assertTrue(tolerated.summary().contains("incomplete"),
                "and it still says what is missing");
    }

    @Test
    void customMemoryHasASafeMinimum() {
        GenerationExecutionSettings settings = new GenerationExecutionSettings(false);
        settings.memoryProfile(GenerationExecutionSettings.MemoryProfile.CUSTOM);
        settings.customMemoryMb(1);

        assertEquals(64, settings.resolvedMemoryMb());
        assertTrue(settings.newFactStore().maxEstimatedBytes() > 0);

        settings.customMemoryMb(20_000);
        assertEquals(8192, settings.resolvedMemoryMb());
    }

    @Test
    void durableDescriptionRecordsResolvedValuesAndPolicy() {
        GenerationExecutionSettings settings = new GenerationExecutionSettings(false);
        settings.memoryProfile(GenerationExecutionSettings.MemoryProfile.CUSTOM);
        settings.customMemoryMb(960);
        settings.networkProfile(GenerationExecutionSettings.NetworkProfile.FAST);
        settings.requireComplete(false);

        String description = settings.resolvedDescription();
        assertTrue(description.contains("cache 960 MB (custom)"));
        assertTrue(description.contains("network fast (10 concurrent entity requests)"));
        assertTrue(description.contains("keep partial results"));
        // Only what the policy owns. Whether a run could resume is the executor's
        // wiring, and a durable log asserting it would keep asserting it after that
        // wiring changes.
        assertFalse(description.contains("checkpoint"),
                "the policy does not speak for the executor");
    }

    @Test
    void generatePlanReadsTheSettingsSelectedBeforeExecute() {
        GenerationExecutionSettings settings = new GenerationExecutionSettings(false);
        GeneratedProjectModel model = new GeneratedProjectModel();
        GenerateDomainProcess process = new GenerateDomainProcess(
                model, GenerateDomainPipeline.configured(model), settings);

        settings.memoryProfile(GenerationExecutionSettings.MemoryProfile.CUSTOM);
        settings.customMemoryMb(960);
        settings.networkProfile(GenerationExecutionSettings.NetworkProfile.FAST);

        assertEquals("960", process.plan().parameters().get("cacheMb"));
        assertEquals("10", process.plan().parameters().get("entityConcurrency"));
    }

    private static GenerationRun runWithWarning() {
        return new GenerationRun(
                new GeneratedProjectModel(), 1, null, List.of(), null, List.of(),
                null, List.of(),
                GenerationRun.Quality.partial(
                        List.of("3 batches unreachable"), List.of("Q1")));
    }

    /** The decision both processes make, called the way they call it. */
    private static ProcessOutcome<GenerationRun> outcomeFor(
            GenerationRun run, boolean requireComplete) {
        GenerationExecutionSettings settings = new GenerationExecutionSettings(false);
        settings.requireComplete(requireComplete);
        return RunCompleteness.decide(
                ProcessOutcome.succeeded(run, "done"), settings.requireComplete());
    }
}
