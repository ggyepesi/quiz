package batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFileBatchCheckpointStoreTest {
    @TempDir Path directory;

    @Test
    void roundTripsAndDeletesCheckpoint() throws Exception {
        JsonFileBatchCheckpointStore store = new JsonFileBatchCheckpointStore(directory);
        WorkDescriptor descriptor = new WorkDescriptor(
                "qid-batch", "Q1,Q2", "QIDs 1-2", Map.of("qids", "Q1,Q2"));
        BatchCheckpoint checkpoint = new BatchCheckpoint(
                "movie-locations",
                List.of(new BatchCheckpoint.PendingWork(descriptor, 3)),
                Set.of("Q0"));

        store.save(checkpoint);
        assertEquals(checkpoint, store.load("movie-locations").orElseThrow());

        store.delete("movie-locations");
        assertTrue(store.load("movie-locations").isEmpty());
    }
}
