package quiz.transform.app;

import dataset.DomainStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.transform.ui.DomainEntry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A project is model-backed because its files say so, not because a registry row exists.
 *
 * <p>TransformApp's catalog listed the registry alone, so Historical Positions — saved in
 * {@code data/wikidata/historicalpositions/} with no dataset row — could not be opened as
 * the project it is. The only way in was a detached snapshot, and a working set opened
 * that way has no owning project, so saving it wrote another detached export instead of
 * the project's own files: the model-backed save path could not be reached at all.
 */
class ASavedProjectIsListedWithoutARegistryRowTest {

    @Test void aProjectOnDiskIsListedAndOpenedWithItsOwnModelAndSnapshot(
            @TempDir Path workspace) throws Exception {
        DomainStorage storage = project(workspace, "Offices", true);

        List<DomainEntry> listed = DomainCatalog.unregisteredProjects(
                storage, Set.of(), name -> new File("nowhere.snapshot.json"));

        assertEquals(1, listed.size(), "the project is listed from its files");
        assertEquals("Offices", listed.getFirst().name());
        assertTrue(listed.getFirst().loadDescription().contains("offices.snapshot.json"),
                "it opens its own instances: " + listed.getFirst().loadDescription());
        assertTrue(listed.getFirst().loadDescription().contains("offices.model.json"),
                "and its own model, which is what makes a save model-backed");
    }

    @Test void aProjectWhoseInstancesWereDiscardedOpensOnItsTransformExport(
            @TempDir Path workspace) throws Exception {
        DomainStorage storage = project(workspace, "Offices", false);
        File export = workspace.resolve("data/wikidata/transform/offices.snapshot.json")
                .toFile();
        export.getParentFile().mkdirs();
        Files.writeString(export.toPath(), "{}");

        List<DomainEntry> listed = DomainCatalog.unregisteredProjects(
                storage, Set.of(), name -> export);

        assertEquals(1, listed.size(),
                "a project whose instances were discarded is still openable");
        assertTrue(listed.getFirst().loadDescription().contains(export.getPath()),
                "on the latest same-named export, so an explicit Save consolidates it "
                        + "back into the project: " + listed.getFirst().loadDescription());
        assertTrue(listed.getFirst().loadDescription().contains("offices.model.json"),
                "still paired with its own model, which is what makes that save "
                        + "model-backed rather than another detached export");
    }

    @Test void aProjectWithNeitherInstancesNorAnExportIsNotOffered(
            @TempDir Path workspace) throws Exception {
        DomainStorage storage = project(workspace, "Offices", false);

        assertTrue(DomainCatalog.unregisteredProjects(storage, Set.of(),
                        name -> new File("nowhere.snapshot.json")).isEmpty(),
                "there is nothing to open");
    }

    @Test void aProjectTheCatalogAlreadyListedIsNotListedTwice(
            @TempDir Path workspace) throws Exception {
        DomainStorage storage = project(workspace, "Offices", true);

        assertTrue(DomainCatalog.unregisteredProjects(storage, Set.of("Offices"),
                name -> new File("nowhere.snapshot.json")).isEmpty());
    }

    private static DomainStorage project(
            Path workspace, String name, boolean withInstances) throws Exception {
        File root = workspace.resolve("data/wikidata").toFile();
        DomainStorage storage = DomainStorage.in(root);
        File model = new File(root, "offices/offices.model.json");
        model.getParentFile().mkdirs();
        Files.writeString(model.toPath(),
                "{\"name\":\"" + name + "\",\"projectKind\":\"DOMAIN\"}");
        if (withInstances) {
            Files.writeString(new File(root, "offices/offices.snapshot.json").toPath(), "{}");
        }
        return storage;
    }
}
