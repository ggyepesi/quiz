package process;

/** Explicit lifecycle states shared by processes and subprocesses. */
public enum ProcessStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == PARTIAL
                || this == FAILED || this == CANCELLED;
    }
}
