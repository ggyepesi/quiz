package quiz.transform.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.curation.ManualCuration;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.model.PopulationSelection;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A population created in TransformApp lands in the domain's working model, and only
 * Save writes the model file.
 *
 * <p>It used to be written on the spot from a fresh read of model.json — a second
 * persistence path beside Save, which also overwrote whatever ModelBuilder had saved in
 * between (#305). ModelBuilder's Create population selection already worked this way.
 */
class PopulationSelectionStoreTest {
    @TempDir Path directory;

    private CuratableDomain domainOver(java.io.File modelFile, Path root) {
        return new CuratableDomain(
                new SnapshotDomain(List.of()),
                new ManualCuration(root.resolve("positions.curation.json").toFile()),
                List.of(), List.of(), modelFile);
    }

    @Test void aCreatedPopulationIsWrittenOnlyBySave() throws Exception {
        var modelFile = directory.resolve("positions.model.json").toFile();
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        new GeneratedProjectModelStore().save(model, modelFile);
        CuratableDomain domain = domainOver(modelFile, directory);

        domain.createPopulationSelection(
                "PositionsForHistory", "Position", List.of("Q1", "Q2"));

        assertNull(new GeneratedProjectModelStore().load(modelFile)
                        .findSelection("PositionsForHistory"),
                "creating changes the working model, not the file");
        assertNotNull(domain.projectModel().findSelection("PositionsForHistory"));
        assertEquals(List.of("Population selection \"PositionsForHistory\": "
                + "2 Position instance QIDs"), domain.unsavedModelChanges());

        domain.writeProjectModel();

        PopulationSelection saved = (PopulationSelection)
                new GeneratedProjectModelStore().load(modelFile)
                        .findSelection("PositionsForHistory");
        assertEquals("Position", saved.className());
        assertEquals(List.of("Q1", "Q2"), saved.instanceQids());
        assertEquals(List.of(), domain.unsavedModelChanges());
    }

    /** ModelBuilder saved the same model while TransformApp had it open. Writing the
     *  working model would silently discard that save, so Save refuses and says why. */
    @Test void saveIsRefusedWhenTheModelChangedOnDiskSinceItWasRead() throws Exception {
        var modelFile = directory.resolve("positions.model.json").toFile();
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(model, modelFile);
        CuratableDomain domain = domainOver(modelFile, directory);
        domain.createPopulationSelection("Held", "Position", List.of("Q1"));

        model.addClass(new GeneratedClassModel("Person"));   // the other application
        store.save(model, modelFile);

        assertTrue(domain.modelWriteConflict().contains(modelFile.getPath()),
                domain.modelWriteConflict());
        assertThrows(IllegalStateException.class, domain::writeProjectModel);
        GeneratedProjectModel onDisk = store.load(modelFile);
        assertNotNull(onDisk.findClass("Person"), "the other application's save survives");
        assertNull(onDisk.findSelection("Held"));
    }

    @Test void transformDomainReportsTheOwningProjectsKind(@TempDir Path root)
            throws Exception {
        var modelFile = root.resolve("positions.model.json").toFile();
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Historical Positions");
        model.projectKind(GeneratedProjectModel.ProjectKind.MODEL);
        new GeneratedProjectModelStore().save(model, modelFile);
        CuratableDomain domain = domainOver(modelFile, root);

        quiz.transform.ui.ProjectBacking backing =
                domain.capability(quiz.transform.ui.ProjectBacking.class);
        assertEquals("Historical Positions", backing.projectName());
        assertSame(GeneratedProjectModel.ProjectKind.MODEL, backing.projectKind());
        assertEquals(root.resolve("positions.snapshot.json").toFile(), backing.snapshotFile());
        String plan = new DomainSaver().describeSave(
                "Historical Positions", List.of(), domain);
        assertTrue(plan.startsWith("Save model \"Historical Positions\""), plan);
        assertTrue(plan.contains(modelFile.getPath()), plan);
        assertTrue(plan.contains(root.resolve("positions.snapshot.json").toString()), plan);
    }
}
