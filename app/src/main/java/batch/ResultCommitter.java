package batch;

/**
 * Applies one fully computed result to caller-owned state. A commit must be idempotent for
 * its descriptor: the process can stop after commit but before the checkpoint is advanced.
 * Registry merges should therefore use the descriptor key/QID as their identity.
 */
@FunctionalInterface
public interface ResultCommitter<R> {
    void commit(WorkDescriptor descriptor, R result) throws Exception;

    static <R> ResultCommitter<R> noOp() {
        return (descriptor, result) -> { };
    }
}
