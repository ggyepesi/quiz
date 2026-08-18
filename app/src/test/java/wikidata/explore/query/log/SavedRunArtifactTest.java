package wikidata.explore.query.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.ProcessWorkflowPipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SavedRunArtifactTest {
    @TempDir Path temp;

    @Test void roundTripsLogAndPipelineSnapshot() throws Exception {
        ProcessWorkflowPipeline pipeline = new ProcessWorkflowPipeline(List.of(
                new ProcessWorkflowPipeline.Phase(
                        "fetch", "Fetch", "Acquire facts", List.of("Person — P31, P569"))));
        pipeline.start("fetch", "50 entities");
        pipeline.complete("fetch", "50 entities loaded");
        SavedRunArtifact original = SavedRunArtifact.capture("request [OK]\n", List.of(
                new SavedRunArtifact.PipelineRun("Generate oscars", pipeline.snapshot())));
        Path file = temp.resolve("query-log.run.json");

        original.write(file);
        SavedRunArtifact loaded = SavedRunArtifact.read(file);

        assertEquals("request [OK]\n", loaded.logText());
        assertEquals("Generate oscars", loaded.pipelines().getFirst().title());
        var phase = loaded.pipelines().getFirst().phases().getFirst();
        assertEquals("fetch", phase.phase().id());
        assertEquals(ProcessWorkflowPipeline.Status.COMPLETED, phase.status());
        assertEquals("50 entities loaded", phase.summary());
    }

    @Test void openingTextUsesCompanionWhenPresent() throws Exception {
        Path text = temp.resolve("query-log.txt");
        Files.writeString(text, "old text");
        SavedRunArtifact.capture("structured text", List.of())
                .write(SavedRunArtifact.companionPath(text));

        assertEquals("structured text", SavedRunArtifact.read(text).logText());
    }

    @Test void opensLegacyTextWithAnExplicitLimitedPipeline() throws Exception {
        Path text = temp.resolve("old-query-log.txt");
        Files.writeString(text, "Generate domain [OK]\n");

        SavedRunArtifact loaded = SavedRunArtifact.read(text);

        assertEquals("Generate domain [OK]\n", loaded.logText());
        assertEquals(1, loaded.pipelines().size());
        assertTrue(loaded.pipelines().getFirst().title().contains("limited"));
    }

    @Test void restoredRunningPhaseIsFrozenAsCancelled() {
        var phase = new ProcessWorkflowPipeline.Phase("a", "A", "", List.of());
        var restored = ProcessWorkflowPipeline.restored(List.of(
                new ProcessWorkflowPipeline.PhaseState(
                        phase, ProcessWorkflowPipeline.Status.RUNNING, "saved", 123, 456)));

        var state = restored.snapshot().getFirst();
        assertEquals(ProcessWorkflowPipeline.Status.CANCELLED, state.status());
        assertEquals(456, state.elapsedMillis());
    }
}
