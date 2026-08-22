package process;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import process.ProcessWorkflowPipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Durable, read-only representation of query text and its executable pipelines. */
public record SavedRunArtifact(
        int formatVersion,
        String savedAt,
        String logText,
        List<PipelineRun> pipelines) {

    public static final int CURRENT_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public SavedRunArtifact {
        savedAt = savedAt == null ? "" : savedAt;
        logText = logText == null ? "" : logText;
        pipelines = pipelines == null ? List.of() : List.copyOf(pipelines);
    }

    public record PipelineRun(String title,
                              List<ProcessWorkflowPipeline.PhaseState> phases) {
        public PipelineRun {
            title = title == null || title.isBlank() ? "Pipeline" : title;
            phases = phases == null ? List.of() : List.copyOf(phases);
        }
    }

    public static SavedRunArtifact capture(String text, List<PipelineRun> pipelines) {
        return new SavedRunArtifact(CURRENT_VERSION, Instant.now().toString(), text, pipelines);
    }

    public void write(Path path) throws java.io.IOException {
        JSON.writeValue(path.toFile(), this);
    }

    public static SavedRunArtifact read(Path selected) throws java.io.IOException {
        Path path = selected;
        if (!selected.getFileName().toString().endsWith(".json")) {
            Path companion = companionPath(selected);
            if (Files.isRegularFile(companion)) path = companion;
            else return fromLegacyText(Files.readString(selected));
        }
        SavedRunArtifact artifact = JSON.readValue(path.toFile(), SavedRunArtifact.class);
        if (artifact.formatVersion() > CURRENT_VERSION) {
            throw new java.io.IOException("Unsupported run artifact version: "
                    + artifact.formatVersion());
        }
        return artifact;
    }

    public static Path companionPath(Path textPath) {
        String name = textPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return textPath.resolveSibling(stem + ".run.json");
    }

    /** Existing plain logs remain useful; their phase timing was never persisted. */
    public static SavedRunArtifact fromLegacyText(String text) {
        ProcessWorkflowPipeline.Phase phase = new ProcessWorkflowPipeline.Phase(
                "legacy-log", "Saved query log",
                "This text-only log predates structured pipeline snapshots; phase timing "
                        + "and exact pipeline state cannot be reconstructed.",
                List.of("Search the Log tab for operation headings and failures."));
        ProcessWorkflowPipeline.PhaseState state = new ProcessWorkflowPipeline.PhaseState(
                phase, ProcessWorkflowPipeline.Status.COMPLETED,
                "Loaded from legacy text", 1, 0);
        return capture(text, List.of(new PipelineRun(
                "Legacy query log (limited diagram)", List.of(state))));
    }
}
