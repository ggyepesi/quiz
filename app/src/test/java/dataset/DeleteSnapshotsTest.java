package dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.DatasetRegistry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discarding a project's generated instances: every snapshot goes, the configuration
 * stays, and nothing is left registered pointing at a file that no longer exists.
 *
 * <p>A graph annotation snapshot counts as generated instances too — it is one run's
 * record of a population chosen by evidence tests that the configuration change has just
 * replaced, so leaving it behind would keep exactly the stale artifact this removes.
 */
class DeleteSnapshotsTest {

    private static File write(File dir, String name) throws Exception {
        dir.mkdirs();
        File file = new File(dir, name);
        Files.writeString(file.toPath(), "{}");
        return file;
    }

    private static DomainStorage storageIn(Path root) {
        return DomainStorage.in(root.toFile());
    }

    @Test void everySnapshotGoesAndTheConfigurationStays(@TempDir Path root) throws Exception {
        DomainStorage storage = storageIn(root);
        File dir = storage.directory("Historical Positions");
        File model = write(dir, "historicalpositions.model.json");
        File ruleTree = write(dir, "historicalpositions.ruletree.json");
        File counts = write(dir, "historicalpositions.counts.tsv");
        File snapshot = write(dir, "historicalpositions.snapshot.json");
        File graph = write(dir, "positionfilter.graph.snapshot.json");

        List<File> removed = storage.deleteSnapshots("Historical Positions");

        assertEquals(List.of(snapshot, graph), removed);
        assertFalse(snapshot.exists(), "the domain snapshot is gone");
        assertFalse(graph.exists(), "the graph annotation snapshot is gone too");
        assertTrue(model.exists(), "the model is configuration, not instances");
        assertTrue(ruleTree.exists(), "so is the rule tree it compiles to");
        assertTrue(counts.exists(),
                "counts.tsv is the history of what was generated, and reading it is how "
                        + "a change is confirmed to have moved the numbers");
    }

    @Test void nothingStaysRegisteredPointingAtADeletedFile(@TempDir Path root)
            throws Exception {
        DomainStorage storage = storageIn(root);
        File dir = storage.directory("Historical Positions");
        File snapshot = write(dir, "historicalpositions.snapshot.json");
        File graph = write(dir, "positionfilter.graph.snapshot.json");

        DatasetRegistry registry = new DatasetRegistry();
        for (File file : List.of(snapshot, graph)) {
            DatasetRegistry.Dataset dataset = new DatasetRegistry.Dataset();
            dataset.name(file.getName());
            dataset.key(file.getName());
            dataset.snapshotPath(file.getPath());
            registry.upsert(dataset);
        }
        DatasetRegistry.Dataset elsewhere = new DatasetRegistry.Dataset();
        elsewhere.name("Oscars");
        elsewhere.key("oscars");
        elsewhere.snapshotPath(new File(root.toFile(), "oscars/oscars.snapshot.json").getPath());
        registry.upsert(elsewhere);
        registry.save(storage.registryFile());

        storage.deleteSnapshots("Historical Positions");

        DatasetRegistry after = DatasetRegistry.load(storage.registryFile());
        assertEquals(List.of("Oscars"),
                after.datasets().stream().map(DatasetRegistry.Dataset::name).toList(),
                "only the entries serving the deleted files are dropped");
    }

    @Test void aProjectThatNeverGeneratedIsUnharmed(@TempDir Path root) throws Exception {
        DomainStorage storage = storageIn(root);
        write(storage.directory("Drafts"), "drafts.model.json");
        assertEquals(List.of(), storage.snapshotFiles("Drafts"));
        assertEquals(List.of(), storage.deleteSnapshots("Drafts"));
    }
}
