package wikidata.explore.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dataset.DomainStorage;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.model.TransformationClassSource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage and staleness record for a transformation's output and for the populations
 * published from its class. Each file has one producer: RUN_TRANSFORMATION writes the
 * class-keyed output, PUBLISH_POPULATION the selection-keyed publication.
 */
public final class MaterializedPopulationStore {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private MaterializedPopulationStore() { }

    @FunctionalInterface
    interface FileReplacer {
        void replace(File from, File to) throws Exception;
    }

    public record Metadata(String transformationSignature, String inputSnapshotSignature,
                           int members) { }

    public static String signature(GeneratedClassModel outputClass) {
        TransformationClassSource transformation = outputClass == null
                ? null : outputClass.transformationSource();
        if (transformation == null) return "";
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("input", transformation.inputClassId());
            value.put("steps", transformation.steps().stream().map(step -> Map.of(
                    "kind", step.kind().name(),
                    "field", step.fieldPath(),
                    "operator", step.operator().name(),
                    "value", String.valueOf(step.value()),
                    "value2", String.valueOf(step.value2()))).toList());
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    JSON.writeValueAsBytes(value));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException("Cannot fingerprint transformation", impossible);
        }
    }

    /** Writes the transformation's own output: exactly what its steps kept. */
    public static List<String> save(GeneratedProjectModel model,
                                    GeneratedClassModel outputClass,
                                    List<WikidataDynamicObject> population,
                                    DomainStorage storage,
                                    String inputSnapshotSignature) throws Exception {
        return write(model, outputClass, population, inputSnapshotSignature,
                storage.materializedPopulationFile(model.name(), outputClass.declarationId()),
                storage.materializedPopulationMetadataFile(
                        model.name(), outputClass.declarationId()));
    }

    /**
     * Writes what {@code population} publishes: the class's members at publication, which
     * may include memberships later steps added to the transformation's output. Its
     * metadata records the transformation and snapshot it was drawn from, so an importer
     * can tell when it is stale; it never stands in for the transformation's own output.
     */
    public static List<String> publish(GeneratedProjectModel model,
                                       PopulationSelection population,
                                       GeneratedClassModel outputClass,
                                       List<WikidataDynamicObject> members,
                                       DomainStorage storage,
                                       String inputSnapshotSignature) throws Exception {
        return write(model, outputClass, members, inputSnapshotSignature,
                storage.publishedPopulationFile(model.name(), population.declarationId()),
                storage.publishedPopulationMetadataFile(
                        model.name(), population.declarationId()));
    }

    /**
     * Writes the members and then their metadata, each through a temporary sibling and one
     * rename. The old metadata is removed first, so an interruption between the two leaves
     * members without metadata — read as missing and rebuilt — never new members under the
     * old signatures, which a reader would take for current.
     */
    private static List<String> write(GeneratedProjectModel model,
                                      GeneratedClassModel outputClass,
                                      List<WikidataDynamicObject> members,
                                      String inputSnapshotSignature,
                                      File output, File metadata) throws Exception {
        if (output.getParentFile() != null) output.getParentFile().mkdirs();
        File outputTemp = new File(output.getPath() + ".tmp");
        File metadataTemp = new File(metadata.getPath() + ".tmp");
        try {
            new WikidataDynamicObjectJsonStore().saveWithFieldGraph(members, outputTemp, model);
            JSON.writeValue(metadataTemp, new Metadata(signature(outputClass),
                    inputSnapshotSignature == null ? "" : inputSnapshotSignature,
                    members == null ? 0 : members.size()));
            replacePair(outputTemp, output, metadataTemp, metadata,
                    DomainStorage::replaceAtomically);
        } finally {
            java.nio.file.Files.deleteIfExists(outputTemp.toPath());
            java.nio.file.Files.deleteIfExists(metadataTemp.toPath());
        }
        return List.of(output.getPath(), metadata.getPath());
    }

    /**
     * Commits the members/metadata pair without sacrificing the last complete pair when a
     * replacement fails. Metadata is still removed before the first replacement, so a reader
     * can never accept new members under old signatures; private backups restore the exact
     * previous state before the failure escapes to the caller.
     */
    static void replacePair(File stagedOutput, File output,
                            File stagedMetadata, File metadata,
                            FileReplacer replacer) throws Exception {
        File outputBackup = null;
        File metadataBackup = null;
        // Preparing recovery is not part of the commit. If either backup cannot be made,
        // both live files are still untouched and the write stops there.
        try {
            outputBackup = backup(output);
            metadataBackup = backup(metadata);
        } catch (Exception failed) {
            deleteBackup(outputBackup);
            deleteBackup(metadataBackup);
            throw failed;
        }
        boolean committed = false;
        try {
            java.nio.file.Files.deleteIfExists(metadata.toPath());
            replacer.replace(stagedOutput, output);
            replacer.replace(stagedMetadata, metadata);
            committed = true;
        } catch (Exception failed) {
            // A replacer can fail before or after changing its target. First make any new
            // metadata invisible. The old metadata describes only the old members, so it
            // returns only once they have: otherwise the population stays without metadata,
            // which reads as missing and is rebuilt, never as current.
            try {
                java.nio.file.Files.deleteIfExists(metadata.toPath());
            } catch (Exception restoring) {
                failed.addSuppressed(restoring);
            }
            if (restore(outputBackup, output, replacer, failed)) {
                restore(metadataBackup, metadata, replacer, failed);
            }
            throw failed;
        } finally {
            // A restore consumes its backup; one left after a failure is the only remaining
            // copy of the previous pair, so it stays for recovery.
            if (committed) {
                deleteBackup(outputBackup);
                deleteBackup(metadataBackup);
            }
        }
    }

    private static File backup(File target) throws java.io.IOException {
        if (target == null || !target.isFile()) return null;
        java.nio.file.Path parent = target.toPath().toAbsolutePath().getParent();
        java.nio.file.Path copy = java.nio.file.Files.createTempFile(
                parent, target.getName() + ".previous-", ".tmp");
        java.nio.file.Files.delete(copy);
        try {
            // Population snapshots can be large. A sibling hard link banks the old inode
            // without copying it; filesystems that do not support links use an ordinary copy.
            java.nio.file.Files.createLink(copy, target.toPath());
        } catch (UnsupportedOperationException | java.io.IOException cannotLink) {
            try {
                java.nio.file.Files.copy(target.toPath(), copy,
                        java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            } catch (java.io.IOException failed) {
                failed.addSuppressed(cannotLink);
                java.nio.file.Files.deleteIfExists(copy);
                throw failed;
            }
        }
        return copy.toFile();
    }

    /** Moves the private sibling backup back into place: atomic, and never a copy of a
     *  large snapshot. A target that had no previous file is removed instead. */
    private static boolean restore(File backup, File target, FileReplacer replacer,
                                   Exception failed) {
        try {
            if (backup == null) {
                java.nio.file.Files.deleteIfExists(target.toPath());
            } else {
                replacer.replace(backup, target);
            }
            return true;
        } catch (Exception restoring) {
            failed.addSuppressed(restoring);
            return false;
        }
    }

    private static void deleteBackup(File backup) {
        if (backup == null) return;
        try {
            java.nio.file.Files.deleteIfExists(backup.toPath());
        } catch (java.io.IOException ignored) {
            backup.deleteOnExit();
        }
    }

    public static Metadata metadata(GeneratedProjectModel model,
                                    GeneratedClassModel outputClass,
                                    DomainStorage storage) {
        return read(storage.materializedPopulationMetadataFile(
                model.name(), outputClass.declarationId()));
    }

    public static Metadata publication(GeneratedProjectModel model,
                                       PopulationSelection population,
                                       DomainStorage storage) {
        return read(storage.publishedPopulationMetadataFile(
                model.name(), population.declarationId()));
    }

    /** The metadata in {@code file}, or null when there is none. A file that exists but
     *  cannot be read is reported as unreadable, naming it — never as absent, which would
     *  send the reader to rebuild something that is present and damaged. */
    private static Metadata read(File file) {
        if (!file.isFile()) return null;
        try {
            return JSON.readValue(file, Metadata.class);
        } catch (Exception unreadable) {
            throw new IllegalStateException("Cannot read population metadata "
                    + file.getPath() + ": " + unreadable.getMessage(), unreadable);
        }
    }

    public static List<WikidataDynamicObject> load(GeneratedProjectModel model,
                                                   GeneratedClassModel outputClass,
                                                   DomainStorage storage) throws Exception {
        return new WikidataDynamicObjectJsonStore().load(
                storage.materializedPopulationFile(
                        model.name(), outputClass.declarationId()));
    }

    public static List<WikidataDynamicObject> loadPublished(
            GeneratedProjectModel model, PopulationSelection population,
            DomainStorage storage) throws Exception {
        return new WikidataDynamicObjectJsonStore().load(
                storage.publishedPopulationFile(model.name(), population.declarationId()));
    }
}
