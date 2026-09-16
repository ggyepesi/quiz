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
}
