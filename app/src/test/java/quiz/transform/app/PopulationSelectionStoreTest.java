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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Saving an edited Transform selection writes the source model, not a private sidecar. */
class PopulationSelectionStoreTest {
    @TempDir Path directory;

    @Test void savedPopulationRoundTripsThroughTheDomainModel() throws Exception {
        var modelFile = directory.resolve("positions.model.json").toFile();
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        new GeneratedProjectModelStore().save(model, modelFile);
        CuratableDomain domain = new CuratableDomain(
                new SnapshotDomain(List.of()),
                new ManualCuration(directory.resolve("positions.curation.json").toFile()),
                List.of(), List.of(), modelFile);

        domain.savePopulationSelection(
                "PositionsForHistory", "Position", List.of("Q1", "Q2"));

        PopulationSelection saved = (PopulationSelection)
                new GeneratedProjectModelStore().load(modelFile)
                        .findSelection("PositionsForHistory");
        assertEquals("Position", saved.className());
        assertEquals(List.of("Q1", "Q2"), saved.instanceQids());
    }

    @Test void transformDomainReportsTheOwningProjectsKind(@TempDir Path root)
            throws Exception {
        var modelFile = root.resolve("positions.model.json").toFile();
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Historical Positions");
        model.projectKind(GeneratedProjectModel.ProjectKind.MODEL);
        new GeneratedProjectModelStore().save(model, modelFile);
        CuratableDomain domain = new CuratableDomain(
                new SnapshotDomain(List.of()),
                new ManualCuration(root.resolve("positions.curation.json").toFile()),
                List.of(), List.of(), modelFile);

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
