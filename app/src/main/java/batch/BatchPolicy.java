package batch;

/**
 * How hard to try, per run. Deliberately NOT part of the domain model: with correct
 * partitioning the result is identical whatever the batch size, so this is how the work
 * is fetched, not what the domain is. A caller builds one per run.
 *
 * @param maxAttempts   attempts at an unchanged unit before escalating to a split.
 *                      Only {@link BatchFailure#TRANSIENT} failures consume attempts;
 *                      a TOO_HEAVY unit splits at once, since repeating it is futile.
 * @param maxSplitDepth how many times a unit may be halved before the run gives up.
 *                      Bounds the recursion: 6 turns one unit into at most 64 parts.
 * @param retryBackoffMillis pause before re-attempting a transient failure, multiplied
 *                      by the attempt number.
 * @param resume        skip units a previous run already completed (Phase 2).
 */
public record BatchPolicy(
        int maxAttempts,
        int maxSplitDepth,
        long retryBackoffMillis,
        boolean resume) {

    public BatchPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (maxSplitDepth < 0) {
            throw new IllegalArgumentException("maxSplitDepth must be >= 0");
        }
        if (retryBackoffMillis < 0) {
            throw new IllegalArgumentException("retryBackoffMillis must be >= 0");
        }
    }

    /** Three attempts and six splits: enough to ride out a transient truncation, and to
     *  narrow a batch of 100 down to a single member before admitting defeat. */
    public static BatchPolicy defaults() {
        return new BatchPolicy(3, 6, 500L, true);
    }

    public BatchPolicy withResume(boolean resume) {
        return new BatchPolicy(maxAttempts, maxSplitDepth, retryBackoffMillis, resume);
    }

    public BatchPolicy withMaxSplitDepth(int depth) {
        return new BatchPolicy(maxAttempts, depth, retryBackoffMillis, resume);
    }
}
