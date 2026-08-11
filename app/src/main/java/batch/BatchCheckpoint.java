package batch;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Recovered execution frontier. The store derives this view by replaying its append-only
 * START/SPLIT/COMPLETE journal, so each successful unit adds one small record instead of
 * rewriting this whole structure.
 */
public record BatchCheckpoint(
        int version,
        String runKey,
        List<PendingWork> pending,
        Set<String> completedKeys,
        Set<String> knownKeys) {

    public static final int CURRENT_VERSION = 1;

    public BatchCheckpoint {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported checkpoint version: " + version);
        }
        if (runKey == null || runKey.isBlank()) {
            throw new IllegalArgumentException("runKey must not be blank");
        }
        pending = pending == null ? List.of() : List.copyOf(pending);
        completedKeys = completedKeys == null
                ? Set.of()
                : Set.copyOf(new LinkedHashSet<>(completedKeys));
        for (PendingWork work : pending) {
            if (completedKeys.contains(work.descriptor().key())) {
                throw new IllegalArgumentException(
                        "Pending work is already completed: " + work.descriptor().key());
            }
        }
        LinkedHashSet<String> allKnown = knownKeys == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(knownKeys);
        allKnown.addAll(completedKeys);
        pending.forEach(work -> allKnown.add(work.descriptor().key()));
        knownKeys = Set.copyOf(allKnown);
    }

    public BatchCheckpoint(String runKey, List<PendingWork> pending, Set<String> completedKeys) {
        this(CURRENT_VERSION, runKey, pending, completedKeys, null);
    }

    public BatchCheckpoint(
            String runKey,
            List<PendingWork> pending,
            Set<String> completedKeys,
            Set<String> knownKeys) {
        this(CURRENT_VERSION, runKey, pending, completedKeys, knownKeys);
    }

    public record PendingWork(WorkDescriptor descriptor, int splitDepth) {
        public PendingWork {
            if (descriptor == null) {
                throw new IllegalArgumentException("descriptor must not be null");
            }
            if (splitDepth < 0) {
                throw new IllegalArgumentException("splitDepth must be >= 0");
            }
        }
    }
}
