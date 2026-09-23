package wikidata.explore.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dataset.DomainStorage;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.model.GeneratedProjectModel;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The finalized graph boundary immediately before generated Java objects are mapped.
 *
 * <p>Acquisition is the expensive part of generation. Materialization is local and may
 * fail because of generated code or graph shape, so losing the already finalized graph
 * at that boundary would turn a local bug into another remote generation run.
 */
public final class GenerationRecoveryStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final File snapshot;
    private final File metadata;

    public GenerationRecoveryStore(File snapshot, File metadata) {
        this.snapshot = java.util.Objects.requireNonNull(snapshot, "No recovery snapshot");
        this.metadata = java.util.Objects.requireNonNull(metadata, "No recovery metadata");
    }

    public static GenerationRecoveryStore in(DomainStorage storage, String projectName) {
        return new GenerationRecoveryStore(
                storage.generationRecoveryFile(projectName),
                storage.generationRecoveryMetadataFile(projectName));
    }

    public File snapshotFile() {
        return snapshot;
    }

    public boolean canResume(GeneratedProjectModel model) {
        if (!snapshot.isFile() || !metadata.isFile()) return false;
        try {
            Metadata saved = JSON.readValue(metadata, Metadata.class);
            return saved != null && saved.modelSignature != null
                    && saved.modelSignature.equals(DomainSave.signature(model));
        } catch (IOException unreadable) {
            return false;
        }
    }

    public void save(GeneratedProjectModel model, GraphCheckpoint checkpoint,
            wikidata.explore.transform.SelfReferenceLedger selfReferences)
            throws IOException {
        if (checkpoint == null || checkpoint.stage() != GraphCheckpoint.Stage.FINAL_GRAPH) {
            throw new IllegalArgumentException("Recovery requires a final graph");
        }
        File directory = snapshot.getParentFile();
        if (directory != null) Files.createDirectories(directory.toPath());
        File snapshotTemp = new File(snapshot.getPath() + ".tmp");
        File metadataTemp = new File(metadata.getPath() + ".tmp");
        try {
            new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                    checkpoint.objects(), snapshotTemp, model,
                    checkpoint.loadedDeclarations(), checkpoint.graphDiscovery(),
                    selfReferences);
            JSON.writerWithDefaultPrettyPrinter().writeValue(metadataTemp,
                    new Metadata(checkpoint.modelSignature(), checkpoint.quality()));
            move(snapshotTemp, snapshot);
            move(metadataTemp, metadata);
        } finally {
            Files.deleteIfExists(snapshotTemp.toPath());
            Files.deleteIfExists(metadataTemp.toPath());
        }
    }

    public Recovered load(GeneratedProjectModel model) throws IOException {
        Metadata saved = JSON.readValue(metadata, Metadata.class);
        String current = DomainSave.signature(model);
        if (saved == null || saved.modelSignature == null
                || !saved.modelSignature.equals(current)) {
            throw new IOException("The recovery graph was produced by a different model");
        }
        WikidataDynamicObjectJsonStore.LoadedSnapshot loaded =
                new WikidataDynamicObjectJsonStore().loadAllWithFieldGraph(snapshot);
        return new Recovered(loaded, saved.quality == null
                ? GenerationRun.Quality.completeQuality() : saved.quality);
    }

    public void clear() throws IOException {
        Files.deleteIfExists(snapshot.toPath());
        Files.deleteIfExists(metadata.toPath());
    }

    private static void move(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record Recovered(WikidataDynamicObjectJsonStore.LoadedSnapshot snapshot,
                            GenerationRun.Quality quality) { }

    public static final class Metadata {
        public String modelSignature;
        public GenerationRun.Quality quality;

        public Metadata() { }

        Metadata(String modelSignature, GenerationRun.Quality quality) {
            this.modelSignature = modelSignature;
            this.quality = quality;
        }
    }
}
