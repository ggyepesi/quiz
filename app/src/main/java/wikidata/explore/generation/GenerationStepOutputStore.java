package wikidata.explore.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dataset.DomainStorage;
import datasource.graph.GraphDiscoveryState;
import wikidata.explore.extract.LoadedDeclaration;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.SelfReferenceLedger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The output of one Generate step of a build, keyed by the step's declaration id.
 *
 * <p>A build reshapes what a Generate produced: a graph apply narrows it, a transformation
 * filters it, an additive graph expands it, and Save project writes the result as the
 * project snapshot. A Generate that a later build reuses must therefore hand on what it
 * produced, not that snapshot. Historical Positions reused the snapshot and its
 * replacement chain started from a pool the previous build had already expanded, so it
 * reached 18 identities instead of 73 (#423).
 *
 * <p>The step is current while the model and the identities it was asked to acquire are
 * those recorded beside it. The metadata is removed before the output is replaced and
 * written after, so a failed write leaves no metadata, never a new output under an old key.
 */
public final class GenerationStepOutputStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final File snapshot;
    private final File metadata;

    private GenerationStepOutputStore(File snapshot, File metadata) {
        this.snapshot = snapshot;
        this.metadata = metadata;
    }

    public static GenerationStepOutputStore of(DomainStorage storage, String projectName,
                                               String operationDeclarationId) {
        return new GenerationStepOutputStore(
                storage.generationStepFile(projectName, operationDeclarationId),
                storage.generationStepMetadataFile(projectName, operationDeclarationId));
    }

    public File snapshotFile() { return snapshot; }

    /** The recorded key, or null when the output or its metadata is absent or unreadable. */
    public Metadata metadata() {
        if (!snapshot.isFile() || !metadata.isFile()) return null;
        try {
            Metadata saved = JSON.readValue(metadata, Metadata.class);
            return saved == null || saved.modelSignature == null ? null : saved;
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** Writes {@code run} as this step's output; returns the files written. */
    public List<String> save(GeneratedProjectModel model, GenerationRun run,
                             GraphDiscoveryState graphDiscovery,
                             Map<String, List<String>> acquisitions) throws IOException {
        return save(model, run.dynamicObjects(), run.loadedDeclarations(),
                run.selfReferenceAudit().ledger(), graphDiscovery, acquisitions);
    }

    public List<String> save(GeneratedProjectModel model, List<WikidataDynamicObject> objects,
                             List<LoadedDeclaration> loadedDeclarations,
                             SelfReferenceLedger selfReferences,
                             GraphDiscoveryState graphDiscovery,
                             Map<String, List<String>> acquisitions) throws IOException {
        Objects.requireNonNull(model, "model");
        File directory = snapshot.getParentFile();
        if (directory != null) Files.createDirectories(directory.toPath());
        File snapshotTemp = new File(snapshot.getPath() + ".tmp");
        File metadataTemp = new File(metadata.getPath() + ".tmp");
        try {
            new WikidataDynamicObjectJsonStore().saveWithFieldGraph(objects, snapshotTemp,
                    model, loadedDeclarations, graphDiscovery, selfReferences);
            JSON.writerWithDefaultPrettyPrinter().writeValue(metadataTemp,
                    new Metadata(DomainSave.signature(model), acquisitions));
            Files.deleteIfExists(metadata.toPath());
            DomainStorage.replaceAtomically(snapshotTemp, snapshot);
            DomainStorage.replaceAtomically(metadataTemp, metadata);
        } finally {
            Files.deleteIfExists(snapshotTemp.toPath());
            Files.deleteIfExists(metadataTemp.toPath());
        }
        return List.of(snapshot.getPath(), metadata.getPath());
    }

    /** This step's output as a run of {@code model}, recorded as generated from the model
     *  that produced it. */
    public GenerationRun load(GeneratedProjectModel model, DomainStorage storage,
                              Consumer<String> say) throws Exception {
        Metadata recorded = metadata();
        if (recorded == null) {
            throw new IOException("No readable Generate step output at " + snapshot.getPath());
        }
        return ProjectLoad.load(model, storage, snapshot, say).run()
                .generatedFrom(recorded.modelSignature);
    }

    /** The key a Generate step's output is current for. */
    public static final class Metadata {
        public String modelSignature;
        /** Supplemental identities requested, by the class that acquired them. */
        public Map<String, List<String>> acquisitions = Map.of();

        public Metadata() { }

        Metadata(String modelSignature, Map<String, List<String>> acquisitions) {
            this.modelSignature = modelSignature;
            this.acquisitions = normalized(acquisitions);
        }

        /** Whether this output was produced for exactly {@code requested}. */
        public boolean acquired(Map<String, List<String>> requested) {
            return normalized(acquisitions).equals(normalized(requested));
        }

        public int acquiredCount() {
            return acquisitions == null ? 0
                    : acquisitions.values().stream().mapToInt(List::size).sum();
        }
    }

    /** Order-free form, so the same request compares equal however it was assembled. */
    static Map<String, List<String>> normalized(Map<String, List<String>> acquisitions) {
        Map<String, List<String>> sorted = new TreeMap<>();
        if (acquisitions == null) return sorted;
        acquisitions.forEach((className, qids) -> {
            if (className == null || qids == null || qids.isEmpty()) return;
            List<String> values = new ArrayList<>(new java.util.TreeSet<>(qids));
            sorted.put(className, List.copyOf(values));
        });
        return sorted;
    }
}
