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

    @Test void aRememberedSavedDomainWinsAndAMissingOneFallsBack(@TempDir Path root)
            throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        write(storage.modelFile("Constellations"), "{\"name\":\"Constellations\"}");
        write(storage.modelFile("History"), "{\"name\":\"History\"}");

        assertEquals(storage.modelFile("History"),
                storage.preferredModelFile("History", "Constellations"));
        assertEquals(storage.modelFile("Constellations"),
                storage.preferredModelFile("Deleted domain", "Constellations"));
    }

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

    @Test void aSavedDraftModelIsOfferedBeforeItHasASnapshotOrRegistryEntry(
            @TempDir Path root) throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        File history = storage.modelFile("History");
        write(history, "{\"name\":\"History\"}");

        assertEquals(List.of("History"), storage.modelBackedNames());
        assertEquals(history, storage.modelFileOf("History"));
        assertNull(storage.find("History"),
                "an editor draft must not masquerade as a servable dataset triple");
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

    @Test void twoModelsClaimingOneNameAreNotResolvedByPickingOne(@TempDir Path root)
            throws Exception {
        // A stale copy left beside its successor. Choosing the first by folder order
        // would open one and then save over it, making an arbitrary pick authoritative.
        DomainStorage storage = DomainStorage.in(root.toFile());
        File stale = new File(new File(root.toFile(), "elements"), "elements.model.json");
        File current = storage.modelFile("Periodic Table");
        write(stale, "{\"name\":\"Periodic Table\"}");
        write(current, "{\"name\":\"Periodic Table\"}");

        assertEquals(current, storage.modelFileOf("Periodic Table"),
                "the model where the layout says it belongs is what the name means");
        assertEquals(List.of("Periodic Table"), storage.modelBackedNames(),
                "one name, however many files claim it");
        assertEquals(2, storage.modelFilesClaiming("Periodic Table").size());
    }

    @Test void aNameNoConventionalModelClaimsResolvesToNothingThatExists(
            @TempDir Path root) throws Exception {
        // Neither claimant sits where the layout says. Rather than edit whichever
        // sorted first, name a path that is not there and let the caller report it.
        DomainStorage storage = DomainStorage.in(root.toFile());
        write(new File(new File(root.toFile(), "aaa"), "aaa.model.json"),
                "{\"name\":\"Rulers\"}");
        write(new File(new File(root.toFile(), "bbb"), "bbb.model.json"),
                "{\"name\":\"Rulers\"}");

        File resolved = storage.modelFileOf("Rulers");
        assertEquals(storage.modelFile("Rulers"), resolved);
        assertFalse(resolved.isFile(), "a missing file is a better answer than the wrong one");
    }

    @Test void oneClaimantIsStillFoundWhereverItLives(@TempDir Path root)
            throws Exception {
        // The ordinary draft case must not regress: a lone model is located by the
        // name inside it, even when its folder key differs.
        DomainStorage storage = DomainStorage.in(root.toFile());
        File odd = new File(new File(root.toFile(), "hu-rulers"), "hu-rulers.model.json");
        write(odd, "{\"name\":\"Hungarian Rulers\"}");

        assertEquals(odd, storage.modelFileOf("Hungarian Rulers"));
    }

    private static void writeProject(DomainStorage storage, String name, String kind)
            throws IOException {
        write(storage.modelFile(name),
                "{\"name\":\"" + name + "\",\"projectKind\":\"" + kind + "\"}");
    }

    @Test void theKindIsReadFromTheProjectItself(@TempDir Path root) throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        writeProject(storage, "People", "MODEL");
        writeProject(storage, "Nobel", "DOMAIN");
        write(storage.modelFile("Oscars"), "{\"name\":\"Oscars\"}");

        assertTrue(storage.isModelKind("People"));
        assertFalse(storage.isModelKind("Nobel"));
        assertFalse(storage.isModelKind("Oscars"),
                "a project saved before kinds existed is a domain");
        assertFalse(storage.isModelKind("Nothing saved under this name"));
        assertEquals(List.of("People"), storage.modelKindNames());
    }

    /**
     * Copying and importing are different acts with different reach. A copy is a
     * convenience and may start from any project. An import leaves the class owned by
     * the model it names, so only a model can be imported from.
     */
    @Test void copyReachesAnyProjectAndImportOnlyModels(@TempDir Path root)
            throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        writeProject(storage, "People", "MODEL");
        writeProject(storage, "Places", "MODEL");
        writeProject(storage, "Nobel", "DOMAIN");
        writeProject(storage, "Oscars", "DOMAIN");

        assertEquals(List.of("Nobel", "Oscars", "People", "Places"),
                storage.copySourcesFor("Constellations"),
                "a copy may start from any saved project");
        assertEquals(List.of("People", "Places"),
                storage.importSourcesFor("Nobel"),
                "an import names a model that keeps owning the class");
    }

    @Test void aProjectIsNeitherCopiedNorImportedFromItself(@TempDir Path root)
            throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        writeProject(storage, "People", "MODEL");

        assertEquals(List.of(), storage.copySourcesFor("People"));
        assertEquals(List.of(), storage.importSourcesFor("People"));
        assertEquals(List.of(), storage.importSourcesFor("  people  "),
                "the folder key decides identity, so punctuation and case cannot "
                        + "smuggle a project into its own source list");
    }
}
