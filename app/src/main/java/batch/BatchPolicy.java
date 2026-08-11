package batch;

/**
 * How hard to try, per run. Deliberately NOT part of the domain model: with correct
 * partitioning the result is identical whatever the batch size, so this is how the work
 * is fetched, not what the domain is. A caller builds one per run.
 *
 * @param maxAttempts   attempts at an unchanged unit after a TRANSIENT failure, which
 *                      then splits. TOO_HEAVY splits at once.
 * @param maxUnavailableAttempts attempts after an UNAVAILABLE failure, which then fails
 *                      without splitting. Deliberately SEPARATE and smaller, because
 *                      retries here stack on the transport's own: the SPARQL client has
 *                      already retried 429/5xx three times honouring Retry-After before
 *                      the executor ever sees the failure, so a shared budget of 3 would
 *                      quietly mean up to nine requests against an endpoint that is by
 *                      hypothesis overloaded. Two keeps the product visible and bounded.
 * @param maxSplitDepth how many split levels may be created before the run gives up.
 *                      The executor does not assume that a split is binary or even.
 * @param retryBackoffMillis pause before re-attempting a transient failure, multiplied
 *                      by the attempt number.
 * @param resume        resume the exact pending leaf queue saved by a previous run.
 */
public record BatchPolicy(
        int maxAttempts,
        int maxSplitDepth,
        long retryBackoffMillis,
        boolean resume,
        int maxUnavailableAttempts) {

    /** Attempts for a failure kind: UNAVAILABLE has its own, smaller budget. */
    public int attemptsFor(BatchFailure failure) {
        return failure == BatchFailure.UNAVAILABLE ? maxUnavailableAttempts : maxAttempts;
    }

    /** Retry budgets other than UNAVAILABLE's default to {@code maxAttempts}. */
    public BatchPolicy(
            int maxAttempts, int maxSplitDepth, long retryBackoffMillis, boolean resume) {
        this(maxAttempts, maxSplitDepth, retryBackoffMillis, resume,
                Math.min(2, maxAttempts));
    }

    public BatchPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (maxUnavailableAttempts < 1) {
            throw new IllegalArgumentException("maxUnavailableAttempts must be >= 1");
        }
        if (maxSplitDepth < 0) {
            throw new IllegalArgumentException("maxSplitDepth must be >= 0");
        }
        if (retryBackoffMillis < 0) {
            throw new IllegalArgumentException("retryBackoffMillis must be >= 0");
        }
    }

    /** Three attempts and seven binary split levels: enough to ride out a transient
     *  truncation and, for the usual even split, narrow 100 members to singletons. */
    public static BatchPolicy defaults() {
        return new BatchPolicy(3, 7, 500L, true);
    }

    public BatchPolicy withResume(boolean resume) {
        return new BatchPolicy(maxAttempts, maxSplitDepth, retryBackoffMillis, resume,
                maxUnavailableAttempts);
    }

    public BatchPolicy withMaxSplitDepth(int depth) {
        return new BatchPolicy(maxAttempts, depth, retryBackoffMillis, resume,
                maxUnavailableAttempts);
    }

    public BatchPolicy withMaxUnavailableAttempts(int attempts) {
        return new BatchPolicy(maxAttempts, maxSplitDepth, retryBackoffMillis, resume,
                attempts);
    }
}
