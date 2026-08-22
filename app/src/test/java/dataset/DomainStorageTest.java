package dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.DatasetRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What renaming and deleting a domain actually do to the files and the registry.
 *
 * <p>All of it was private to a 2,400-line Swing frame, so none of it could be run: the rename
 * collision, the re-keying of a registry entry, the fallback after a delete, and the rule that
 * turns a name into a folder — which is what makes a collision possible in the first place,
 * since two names differing only in punctuation share a folder. The frame still owns the
 * prompting; this owns what happens after the reader says yes.
 */
class DomainStorageTest {

    @Test void aNameBecomesAFolderKeyByLosingEverythingButLettersAndDigits() {
        assertEquals("greekmyth", DomainStorage.key("Greek Myth"));
        assertEquals("constellations", DomainStorage.key("Constellations"));
        assertEquals("uspresidents", DomainStorage.key("U.S. Presidents"));
        assertEquals("generated", DomainStorage.key(""), "a blank name still needs somewhere");
        assertEquals("generated", DomainStorage.key(null));
        assertEquals("generated", DomainStorage.key("!!!"), "and so does a name with no letters");
    }

    @Test void aDomainsFilesAllSitUnderItsOwnKeyedFolder(@TempDir Path root) {
        DomainStorage storage = DomainStorage.in(root.toFile());

        assertEquals(new File(root.toFile(), "greekmyth"), storage.directory("Greek Myth"));
        assertEquals("greekmyth.model.json", storage.modelFile("Greek Myth").getName());
        assertEquals("greekmyth.ruletree.json", storage.ruleTreeFile("Greek Myth").getName());
        assertEquals("greekmyth.snapshot.json", storage.snapshotFile("Greek Myth").getName());
    }

    @Test void renamingMovesTheFolderAndReKeysTheFilesInsideIt(@TempDir Path root) throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        write(storage.modelFile("Greek Myth"), "{}");
        write(storage.snapshotFile("Greek Myth"), "{}");

        DomainStorage.Rename outcome = storage.rename("Greek Myth", "Hellenic Myth");

        assertInstanceOf(DomainStorage.Rename.Done.class, outcome);
        assertFalse(storage.directory("Greek Myth").exists(), "the old folder is gone");
        assertTrue(storage.modelFile("Hellenic Myth").isFile());
        assertTrue(storage.snapshotFile("Hellenic Myth").isFile());
    }

    /** Two names that differ only in punctuation share a folder, so the destination can be
     *  occupied by a DIFFERENT domain — and moving onto it would take its files. */
    @Test void renamingOntoAnExistingFoldersNameIsRefusedRatherThanMerged(@TempDir Path root)
            throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        write(storage.modelFile("Greek Myth"), "{}");
        write(storage.modelFile("Roman Myth"), "{\"other\":true}");

        DomainStorage.Rename outcome = storage.rename("Greek Myth", "Roman-Myth");

        assertInstanceOf(DomainStorage.Rename.FolderTaken.class, outcome);
        assertEquals("{\"other\":true}", Files.readString(storage.modelFile("Roman Myth").toPath()),
                "the domain already there keeps its files");
        assertTrue(storage.modelFile("Greek Myth").isFile(), "and this one keeps its own");
    }

    @Test void renamingPointsTheRegistryEntryAtTheNewNameKeyAndPaths(@TempDir Path root)
            throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        register(storage, "Greek Myth");

        storage.rename("Greek Myth", "Hellenic Myth");

        DatasetRegistry.Dataset entry = storage.find("Hellenic Myth");
        assertEquals("Hellenic Myth", entry.name());
        assertEquals("hellenicmyth", entry.key());
        assertEquals(storage.modelFile("Hellenic Myth").getPath(), entry.modelPath());
        assertEquals(storage.snapshotFile("Hellenic Myth").getPath(), entry.snapshotPath());
    }

    /** A domain that was never saved has no entry, and renaming it is still fine — the next
     *  save registers it under the new name. */
    @Test void renamingSomethingTheRegistryHasNeverHeardOfSucceedsQuietly(@TempDir Path root) {
        DomainStorage storage = DomainStorage.in(root.toFile());

        assertInstanceOf(DomainStorage.Rename.Done.class, storage.rename("Unsaved", "Still Unsaved"));
        assertNull(storage.find("Still Unsaved"));
    }

    @Test void deletingRemovesTheRegistryEntryAndTheWholeFolder(@TempDir Path root)
            throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        register(storage, "Greek Myth");
        write(storage.snapshotFile("Greek Myth"), "{}");

        storage.delete("Greek Myth");

        assertNull(storage.find("Greek Myth"));
        assertFalse(storage.directory("Greek Myth").exists());
    }

    @Test void afterADeleteThereIsSomethingElseToOpenOrHonestlyNothing(@TempDir Path root)
            throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        register(storage, "Greek Myth");
        register(storage, "Constellations");

        assertEquals("Constellations", storage.anyRegisteredNameOtherThan("Greek Myth"));

        storage.delete("Constellations");
        assertNull(storage.anyRegisteredNameOtherThan("Greek Myth"),
                "nothing left but the one being closed");
    }

    /** ModelBuilder can only open what it could also regenerate. A dataset saved from a
     *  TransformApp working set has no model, and listing it offered a switch that lands on a
     *  file which does not exist. */
    @Test void onlyModelBackedDomainsAreOfferedForOpening(@TempDir Path root) throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        register(storage, "Greek Myth");
        DatasetRegistry registry = DatasetRegistry.load(storage.registryFile());
        DatasetRegistry.Dataset transformOnly = new DatasetRegistry.Dataset();
        transformOnly.name("Working Set");
        transformOnly.key("workingset");
        transformOnly.snapshotPath(storage.snapshotFile("Working Set").getPath());
        registry.datasets().add(transformOnly);
        registry.save(storage.registryFile());

        assertEquals(List.of("Greek Myth"), storage.modelBackedNames());
    }

    @Test void aMissingRegistryMeansNothingIsRegisteredRatherThanAFailure(@TempDir Path root) {
        DomainStorage storage = DomainStorage.in(root.toFile());

        assertEquals(List.of(), storage.modelBackedNames());
        assertNull(storage.find("Anything"));
        assertNull(storage.anyRegisteredNameOtherThan("Anything"));
        assertEquals(storage.modelFile("Anything"), storage.modelFileOf("Anything"),
                "with no entry, the layout says where the model would be");
    }

    private static void register(DomainStorage storage, String name) throws IOException {
        DatasetRegistry registry = DatasetRegistry.load(storage.registryFile());
        DatasetRegistry.Dataset dataset = new DatasetRegistry.Dataset();
        dataset.name(name);
        dataset.key(DomainStorage.key(name));
        dataset.modelPath(storage.modelFile(name).getPath());
        dataset.ruletreePath(storage.ruleTreeFile(name).getPath());
        dataset.snapshotPath(storage.snapshotFile(name).getPath());
        registry.datasets().add(dataset);
        registry.save(storage.registryFile());
        write(storage.modelFile(name), "{}");
    }

    private static void write(File file, String content) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }
}
