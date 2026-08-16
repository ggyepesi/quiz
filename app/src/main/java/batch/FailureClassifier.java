package batch;

import java.io.EOFException;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;

/** Maps wrapped exceptions to executor behavior. */
@FunctionalInterface
public interface FailureClassifier {
    FailureDecision classify(Throwable error);

    /** Transport-neutral defaults. Wikidata callers should use its status-aware adapter. */
    static FailureClassifier standard() {
        return error -> {
            for (Throwable t = error; t != null; t = t.getCause()) {
                if (t instanceof InterruptedException || t instanceof CancellationException) {
                    return FailureDecision.of(BatchFailure.CANCELLED);
                }
                // A response-body timeout says the request arrived but was too heavy.
                // A plain SocketTimeoutException may instead be a connection timeout;
                // it falls through to IOException/UNAVAILABLE and is retried unchanged.
                if (t instanceof HttpTimeoutException
                        || t instanceof ResponseTimeoutException) {
                    return FailureDecision.of(BatchFailure.TOO_HEAVY);
                }
                // The transport-neutral shape of "the response stopped before the payload
                // did" — the same condition WDQS produces as a truncated 200. Without it
                // a caller on any other transport could never reach the retry-unchanged
                // path, which is the behaviour the whole escalation is built around.
                if (t instanceof EOFException) {
                    return FailureDecision.of(BatchFailure.TRANSIENT);
                }
                if (t instanceof ResponseInterruptedException) {
                    return FailureDecision.of(BatchFailure.TRANSIENT);
                }
                if (t instanceof IOException) {
                    return FailureDecision.of(BatchFailure.UNAVAILABLE);
                }
            }
            return Thread.currentThread().isInterrupted()
                    ? FailureDecision.of(BatchFailure.CANCELLED)
                    : FailureDecision.of(BatchFailure.FATAL);
        };
    }
}
