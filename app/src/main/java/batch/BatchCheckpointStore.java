package batch;

import java.util.Optional;

/**
 * Incremental persistence boundary for resumable batch execution. Events are ordered:
 * START establishes roots, SPLIT replaces one pending leaf, COMPLETE removes one leaf after
 * its result is committed, and FINISH closes the run.
 */
public interface BatchCheckpointStore {
    Optional<BatchCheckpoint> recover(String runKey) throws Exception;

    void start(
            String runKey,
            java.util.List<BatchCheckpoint.PendingWork> roots) throws Exception;

    void split(
            String runKey,
            String parentKey,
            java.util.List<BatchCheckpoint.PendingWork> children) throws Exception;

    void complete(String runKey, String workKey) throws Exception;

    void finish(String runKey) throws Exception;

    BatchCheckpointStore NONE = new BatchCheckpointStore() {
        @Override public Optional<BatchCheckpoint> recover(String runKey) {
            return Optional.empty();
        }

        @Override public void start(
                String runKey,
                java.util.List<BatchCheckpoint.PendingWork> roots) { }

        @Override public void split(
                String runKey,
                String parentKey,
                java.util.List<BatchCheckpoint.PendingWork> children) { }

        @Override public void complete(String runKey, String workKey) { }

        @Override public void finish(String runKey) { }
    };
}
