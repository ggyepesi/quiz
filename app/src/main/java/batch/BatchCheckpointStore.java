package batch;

import java.util.Optional;

/** Persistence boundary for resumable batch execution. */
public interface BatchCheckpointStore {
    Optional<BatchCheckpoint> load(String runKey) throws Exception;
    void save(BatchCheckpoint checkpoint) throws Exception;
    void delete(String runKey) throws Exception;

    BatchCheckpointStore NONE = new BatchCheckpointStore() {
        @Override public Optional<BatchCheckpoint> load(String runKey) {
            return Optional.empty();
        }

        @Override public void save(BatchCheckpoint checkpoint) { }

        @Override public void delete(String runKey) { }
    };
}
