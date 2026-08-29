package batch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryBatchCheckpointStoreTest {

    @Test
    void journalsAndRecoversAdaptiveWork() throws Exception {
        InMemoryBatchCheckpointStore store = new InMemoryBatchCheckpointStore();
        store.start("run", List.of(work("parent", 0), work("tail", 0)));
        store.split("run", "parent", List.of(work("left", 1), work("right", 1)));
        store.complete("run", "left");

        BatchCheckpoint checkpoint = store.recover("run").orElseThrow();
        assertEquals(List.of("right", "tail"), checkpoint.pending().stream()
                .map(pending -> pending.descriptor().key()).toList());
        assertEquals(Set.of("left"), checkpoint.completedKeys());
        assertEquals(List.of(
                InMemoryBatchCheckpointStore.EventType.START,
                InMemoryBatchCheckpointStore.EventType.SPLIT,
                InMemoryBatchCheckpointStore.EventType.COMPLETE),
                store.events("run").stream().map(InMemoryBatchCheckpointStore.Event::type)
                        .toList());

        store.complete("run", "right");
        store.complete("run", "tail");
        store.finish("run");
        assertTrue(store.recover("run").isEmpty());
    }

    @Test
    void refusesInvalidTransitionsBeforeAppendingThem() throws Exception {
        InMemoryBatchCheckpointStore store = new InMemoryBatchCheckpointStore();
        store.start("run", List.of(work("a", 0)));

        assertThrows(IOException.class, () -> store.complete("run", "unknown"));
        assertEquals(1, store.events("run").size());
    }

    private static BatchCheckpoint.PendingWork work(String key, int depth) {
        return new BatchCheckpoint.PendingWork(
                new WorkDescriptor("test", key, key, Map.of()), depth);
    }
}
