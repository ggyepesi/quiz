package domain.transform.app;

import dataset.DomainStorage;
import domain.curation.Curatable;
import domain.build.ProjectSave;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.GeneratedProjectModelStore;
import domain.persistence.ProjectSaveGuard;
import domain.runtime.WikidataDynamicObject;
import domain.transform.ui.ProjectBacking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentDomainSaveTest {
    @TempDir Path root;

    @Test void modelBuilderAndTheSharedTransformDatasourceWriterRefuseEachOthersStaleCopy()
            throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = new GeneratedProjectModel();
        model.name("History");
        model.rootClass(new GeneratedClassModel("Position"));
        saveModelBuilder(storage, model, "Q1");
        var open = DomainCatalog.unregisteredProjects(storage, Set.of(), storage::snapshotFile)
                .getFirst().opener();
        var transform = open.open();
        var transformBacking = transform.capability(ProjectBacking.class);
        transformBacking.projectModel().rootClass().alias("Working edit");
        var saver = new DomainSaver(storage);

        saveModelBuilder(storage, model, "Q2");
        var snapshot = storage.snapshotFile("History").toPath();
        var onDisk = Files.readString(snapshot);
        var stale = assertThrows(ProjectSaveGuard.Conflict.class,
                () -> saver.save("History", transform.memberRoots(), transform));
        assertTrue(stale.getMessage().contains(snapshot.toString()));
        assertEquals(onDisk, Files.readString(snapshot));
        assertEquals("Working edit", transformBacking.projectModel().rootClass().alias());

        var datasource = open.open();
        var modelBuilder = ProjectSaveGuard.capture(storage, model.name());
        ((CuratableDomain) datasource).createPopulationSelection(
                "Curated positions", "Position", List.of("Q2"));
        saver.save("History", datasource.memberRoots(), datasource);
        saver.save("History", datasource.memberRoots(), datasource);
        assertThrows(ProjectSaveGuard.Conflict.class, modelBuilder::check);
        assertTrue(new GeneratedProjectModelStore().load(storage.modelFile("History"))
                .findSelection("Curated positions") != null);
    }

    @Test void curationSavedByAnotherWorkbenchSurvivesAStaleCurationSave() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = new GeneratedProjectModel();
        model.name("History");
        model.rootClass(new GeneratedClassModel("Position"));
        saveModelBuilder(storage, model, "Q1");
        var open = DomainCatalog.unregisteredProjects(storage, Set.of(), storage::snapshotFile)
                .getFirst().opener();
        var first = open.open().capability(Curatable.class).curation();
        var secondDomain = open.open();
        var second = secondDomain.capability(Curatable.class).curation();
        second.put("Q1", "description", "new value");
        second.save();
        // A workbench may then save its project in the same session.
        new DomainSaver(storage).save("History", secondDomain.memberRoots(), secondDomain);

        first.put("Q1", "description", "stale value");
        var saved = Files.readString(second.file().toPath());
        var conflict = assertThrows(ProjectSaveGuard.Conflict.class, first::save);
        assertTrue(conflict.getMessage().contains(second.file().getPath()));
        assertEquals(saved, Files.readString(second.file().toPath()));
    }

    private static void saveModelBuilder(DomainStorage storage, GeneratedProjectModel model,
            String qid) throws Exception {
        var member = new WikidataDynamicObject(qid, "Position " + qid);
        member.type("Position");
        var run = new ProjectSave.Run(List.of(member), List.of(), null, model);
        ProjectSave.plan(new ProjectSave.Input(model, run, null, List.of()), storage).write();
    }
}
