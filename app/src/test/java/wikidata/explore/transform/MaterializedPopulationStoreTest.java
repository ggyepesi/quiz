package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A failed two-file population commit preserves the last complete readable pair. */
class MaterializedPopulationStoreTest {

    @TempDir Path root;

    /** The members replacement can succeed before the metadata replacement fails. The store
     *  must restore both old files, rather than leave the old population without metadata. */
    @Test void aFailedPairReplacementRestoresThePreviousCompletePair() throws Exception {
        File output = write("population.snapshot.json", "old members");
        File metadata = write("population.json", "old metadata");
        File stagedOutput = write("population.snapshot.json.tmp", "new members");
        File stagedMetadata = write("population.json.tmp", "new metadata");
        AtomicInteger replacements = new AtomicInteger();

        IOException failed = assertThrows(IOException.class,
                () -> MaterializedPopulationStore.replacePair(
                        stagedOutput, output, stagedMetadata, metadata, (from, to) -> {
                            if (replacements.incrementAndGet() == 2) {
                                throw new IOException("metadata rename failed");
                            }
                            dataset.DomainStorage.replaceAtomically(from, to);
                        }));

        assertEquals("metadata rename failed", failed.getMessage());
        assertEquals("old members", Files.readString(output.toPath()));
        assertEquals("old metadata", Files.readString(metadata.toPath()));
        assertEquals(java.util.Set.of("population.snapshot.json", "population.json"),
                names(), "both backups were consumed by their restores");
    }

    /** When the old members cannot be put back, the old metadata must not be either: it
     *  would describe the new members with the previous signatures, which reads as current.
     *  The population is left without metadata (missing, so rebuilt), and both backups stay
     *  as the only copy of the previous pair. */
    @Test void oldMetadataIsNotRestoredOverMembersThatCouldNotBeRestored() throws Exception {
        File output = write("population.snapshot.json", "old members");
        File metadata = write("population.json", "old metadata");
        File stagedOutput = write("population.snapshot.json.tmp", "new members");
        File stagedMetadata = write("population.json.tmp", "new metadata");
        AtomicInteger replacements = new AtomicInteger();

        IOException failed = assertThrows(IOException.class,
                () -> MaterializedPopulationStore.replacePair(
                        stagedOutput, output, stagedMetadata, metadata, (from, to) -> {
                            int call = replacements.incrementAndGet();
                            if (call == 2) throw new IOException("metadata rename failed");
                            if (call == 3) throw new IOException("members restore failed");
                            dataset.DomainStorage.replaceAtomically(from, to);
                        }));

        assertEquals("metadata rename failed", failed.getMessage());
        assertEquals("members restore failed", failed.getSuppressed()[0].getMessage());
        assertEquals("new members", Files.readString(output.toPath()));
        assertFalse(metadata.exists(), "old metadata would vouch for the new members");
        assertEquals(3, replacements.get(), "metadata restore was not attempted");
        assertEquals(2, names().stream().filter(name -> name.contains(".previous-")).count(),
                "both backups are kept for recovery");
    }

    private java.util.Set<String> names() throws IOException {
        try (var files = Files.list(root)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> !name.endsWith(".json.tmp"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private File write(String name, String value) throws IOException {
        Path path = root.resolve(name);
        Files.writeString(path, value);
        return path.toFile();
    }
}
