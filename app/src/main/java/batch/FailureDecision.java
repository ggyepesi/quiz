package batch;

/** Classification plus an optional server-requested minimum retry delay. */
public record FailureDecision(BatchFailure failure, long retryAfterMillis) {
    public FailureDecision {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        if (retryAfterMillis < 0) {
            retryAfterMillis = 0;
        }
    }

    public static FailureDecision of(BatchFailure failure) {
        return new FailureDecision(failure, 0);
    }
}
