package batch;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable execution frontier. Saving the pending leaf descriptors preserves adaptive
 * splits exactly; a resumed run never has to guess how a completed parent was divided.
 *
 * <p><b>Write cost is quadratic in the number of units.</b> The whole checkpoint —
 * including {@code completedKeys}, which only grows — is rewritten after every unit, so a
 * run of N units writes on the order of N²/2 key entries in total. That is deliberate:
 * saving after each unit is what bounds replay to a single unit, and the alternative
 * (saving every k units) trades that guarantee away. It is comfortable into the low
 * thousands of units; a partitioner that ever produced a unit per MEMBER (tens of
 * thousands) would need the frontier persisted less often, or completed keys stored
 * incrementally, and should not simply inherit this.
 */
public record BatchCheckpoint(
        int version,
        String runKey,
        List<PendingWork> pending,
        Set<String> completedKeys) {

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
    }

    public BatchCheckpoint(String runKey, List<PendingWork> pending, Set<String> completedKeys) {
        this(CURRENT_VERSION, runKey, pending, completedKeys);
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
