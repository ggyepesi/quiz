package batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFileBatchCheckpointStoreTest {
    @TempDir Path directory;

    @Test
    void replaysAdaptiveSplitsAndCompletionsInExecutionOrder() throws Exception {
        JsonFileBatchCheckpointStore store = new JsonFileBatchCheckpointStore(directory);
        BatchCheckpoint.PendingWork parent = work("parent", 0);
        BatchCheckpoint.PendingWork tail = work("tail", 0);
        BatchCheckpoint.PendingWork a = work("a", 1);
        BatchCheckpoint.PendingWork b = work("b", 1);

        store.start("movie-locations", List.of(parent, tail));
        store.split("movie-locations", "parent", List.of(a, b));
        store.complete("movie-locations", "a");

        BatchCheckpoint recovered = store.recover("movie-locations").orElseThrow();
        assertEquals(List.of("b", "tail"), recovered.pending().stream()
                .map(p -> p.descriptor().key()).toList());
        assertEquals(Set.of("a"), recovered.completedKeys());
        assertEquals(Set.of("parent", "a", "b", "tail"),
                     recovered.knownKeys());
        assertEquals(1, recovered.pending().getFirst().splitDepth());

        store.complete("movie-locations", "b");
        store.complete("movie-locations", "tail");
        store.finish("movie-locations");
        assertTrue(store.recover("movie-locations").isEmpty());
    }

    @Test
    void eachCompletionAppendsAConstantSizeRecord() throws Exception {
        JsonFileBatchCheckpointStore store = new JsonFileBatchCheckpointStore(directory);
        List<BatchCheckpoint.PendingWork> roots = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            roots.add(work(String.format("Q%03d", i), 0));
        }

        store.start("linear-write", roots);
        Path journal = journal();
        long afterStart = Files.size(journal);
        store.complete("linear-write", "Q000");
        long afterFirst = Files.size(journal);
        store.complete("linear-write", "Q001");
        long afterSecond = Files.size(journal);

        assertEquals(afterFirst - afterStart, afterSecond - afterFirst,
                "completion cost must not grow with pending or completed unit count");
        assertTrue(afterSecond - afterFirst < 512,
                "a completion should be one small journal record");
    }

    @Test
    void ignoresAnIncompleteTrailingFrame() throws Exception {
        JsonFileBatchCheckpointStore store = new JsonFileBatchCheckpointStore(directory);
        store.start("torn-tail", List.of(work("a", 0)));
        Files.write(journal(), new byte[] { 0x42, 0x43, 0x4a, 0x31, 0x00 },
                StandardOpenOption.APPEND);

        BatchCheckpoint recovered = store.recover("torn-tail").orElseThrow();

        assertEquals(List.of("a"), recovered.pending().stream()
                .map(p -> p.descriptor().key()).toList());
        store.complete("torn-tail", "a");
        assertTrue(store.recover("torn-tail").orElseThrow().pending().isEmpty(),
                "recovery must remove the torn tail before another event is appended");
    }

    @Test
    void migratesThePreviousWholeSnapshotOnce() throws Exception {
        String runKey = "legacy-run";
        BatchCheckpoint legacy = new BatchCheckpoint(
                runKey, List.of(work("pending", 2)), Set.of("already-done"));
        Path legacyFile = directory.resolve(hash(runKey) + ".batch.json");
        new ObjectMapper().writeValue(legacyFile.toFile(), legacy);

        JsonFileBatchCheckpointStore store = new JsonFileBatchCheckpointStore(directory);
        BatchCheckpoint recovered = store.recover(runKey).orElseThrow();

        assertEquals(legacy, recovered);
        assertTrue(Files.notExists(legacyFile));
        assertTrue(Files.exists(journal()));
    }

    private Path journal() throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString()
                            .endsWith(".batch.journal"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static BatchCheckpoint.PendingWork work(String key, int depth) {
        return new BatchCheckpoint.PendingWork(
                new WorkDescriptor(
                        "qid-batch", key, "Work " + key, Map.of("key", key)),
                depth);
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
