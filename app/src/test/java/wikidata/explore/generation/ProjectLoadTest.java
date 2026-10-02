package wikidata.explore.generation;

import dataset.DomainStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.extract.LoadedDeclaration;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loading saved instances is an operation with no window in it, and it reads a snapshot
 * the one way Save also reads it.
 *
 * <p>Load lived in the ModelBuilder frame, and Save carried a second copy of its first half
 * for re-saving a snapshot nobody had loaded. Two readings of one file agree only until one
 * of them is changed; the rename fix (#309) had to be made in both.
 */
class ProjectLoadTest {

    @TempDir Path root;

    @Test void aSavedDomainLoadsAsARunWithItsFetchedDeclarations() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = domain();
        save(model, storage, List.of(new LoadedDeclaration("Star", "label", "P1",
                List.of("Q1", "Q2"))), member("Q1"), member("Q2"));

        ProjectLoad.Loaded loaded = ProjectLoad.load(
                model, storage, storage.snapshotFile(model.name()), null);

        assertEquals(2, loaded.run().instances().size());
        assertEquals(List.of("Star.label:P1"), loaded.run().loadedDeclarations().stream()
                .map(LoadedDeclaration::key).toList());
        assertFalse(loaded.stale(), "loaded through the model that saved it");
        loaded.run().runtime().close();
    }

    /** A class renamed since the Save is the same class: its members and its fetched
     *  declarations follow the new name. */
    @Test void aClassRenamedSinceTheSaveKeepsItsMembers() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = domain();
        save(model, storage, List.of(new LoadedDeclaration("Star", "label", "P1",
                List.of("Q1"))), member("Q1"));

        model.renameClass("Star", "CelestialBody");
        ProjectLoad.Loaded loaded = ProjectLoad.load(
                model, storage, storage.snapshotFile(model.name()), null);

        assertTrue(loaded.run().dynamicObjects().getFirst().directClassNames()
                .contains("CelestialBody"));
        assertEquals("CelestialBody", loaded.run().loadedDeclarations().getFirst().className());
        loaded.run().runtime().close();
    }

    /** The registry records the model a served snapshot came from; loading it through a
     *  changed model says so, as data a headless build can act on. */
    @Test void aSnapshotFromADifferentModelVersionIsReportedStale() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = domain();
        save(model, storage, List.of(), member("Q1"));

        model.rootClass().generationDepth(model.rootClass().generationDepth() + 1);
        ProjectLoad.Loaded loaded = ProjectLoad.load(
                model, storage, storage.snapshotFile(model.name()), null);

        assertTrue(loaded.stale());
        assertTrue(loaded.staleWarning().contains(storage.snapshotFile(model.name()).getPath()));
        loaded.run().runtime().close();
    }

    /** Save with nothing loaded writes back exactly what Load reads. */
    @Test void saveAndLoadReadTheSnapshotTheSameWay() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = domain();
        save(model, storage, List.of(), member("Q1"), member("Q2"));
        model.renameClass("Star", "CelestialBody");

        ProjectSave resave = ProjectSave.plan(
                new ProjectSave.Input(model, null, null, List.of()), storage);
        ProjectLoad.Projection read =
                ProjectLoad.project(model, storage, storage.snapshotFile(model.name()));

        assertEquals(2, resave.instances());
        assertEquals(2, read.objects().size());
    }

    private static void save(GeneratedProjectModel model, DomainStorage storage,
                             List<LoadedDeclaration> declarations,
                             WikidataDynamicObject... members) throws Exception {
        ProjectSave.plan(new ProjectSave.Input(model,
                new ProjectSave.Run(List.of(members), declarations, null, model.copy()),
                null, List.of()), storage).write();
    }

    private static GeneratedProjectModel domain() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Stars");
        model.rootClass().className("Star");
        return model;
    }

    private static WikidataDynamicObject member(String qid) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, "Star " + qid);
        value.type("Star");
        return value;
    }
}
