package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A graph-result snapshot beside a model must never become Load instances' input. */
class ModelBuilderSnapshotSelectionTest {

    @Test void selectsTheSnapshotWithTheModelsExactStem() {
        File model = new File("data/wikidata/historicalpositions/"
                + "historicalpositions.model.json");

        assertEquals(new File("data/wikidata/historicalpositions/"
                        + "historicalpositions.snapshot.json"),
                ModelBuilderFrame.snapshotBesideModel(model));
    }

    @Test void refusesAFileThatIsNotAModel() {
        assertNull(ModelBuilderFrame.snapshotBesideModel(
                new File("data/wikidata/historicalpositions/"
                        + "positionfilter.graph.snapshot.json")));
    }
}
