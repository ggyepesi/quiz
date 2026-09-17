package quiz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a dataset is served is stored, not inferred from being listed.
 *
 * <p>The registry has two readers — the quiz web server and the TransformApp catalog —
 * so being listed used to mean being served, and the only way to make a graph
 * constraint's annotations loadable was to publish a record of a classification run as
 * quiz content. Every dataset written before the flag existed stays servable.
 */
class DatasetRegistryServedTest {

    @Test void adatasetWrittenWithoutTheFlagIsStillServed(@TempDir Path dir) throws Exception {
        File file = dir.resolve("datasets.json").toFile();
        Files.writeString(file.toPath(), """
                {"datasets":[{"name":"Oscars","key":"oscars",
                  "snapshotPath":"oscars.snapshot.json","rootClass":"Nomination"}]}
                """);

        DatasetRegistry registry = DatasetRegistry.load(file);

        assertTrue(registry.datasets().getFirst().served(),
                "a registry saved before the flag existed keeps serving what it listed");
    }

    @Test void theFlagSurvivesBeingSavedAndReloaded(@TempDir Path dir) throws Exception {
        File file = dir.resolve("datasets.json").toFile();
        DatasetRegistry registry = new DatasetRegistry();
        DatasetRegistry.Dataset annotations = new DatasetRegistry.Dataset();
        annotations.name("Historical Positions — PositionValidity");
        annotations.key("historicalpositions--positionvalidity");
        annotations.snapshotPath("positionvalidity.graph.snapshot.json");
        annotations.served(false);
        registry.upsert(annotations);
        registry.save(file);

        assertFalse(DatasetRegistry.load(file).datasets().getFirst().served(),
                "an unserved dataset does not become served by a round trip");
    }
}
