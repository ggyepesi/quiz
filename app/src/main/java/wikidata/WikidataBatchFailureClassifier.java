package wikidata;

import batch.BatchFailure;
import batch.FailureClassifier;
import batch.FailureDecision;

import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;

/** Status-aware batch behavior for failures that escaped the SPARQL transport retries. */
public final class WikidataBatchFailureClassifier implements FailureClassifier {
    public static final WikidataBatchFailureClassifier INSTANCE =
            new WikidataBatchFailureClassifier();

    private final FailureClassifier fallback = FailureClassifier.standard();

    private WikidataBatchFailureClassifier() { }

    @Override
    public FailureDecision classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof CancellationException) {
                return FailureDecision.of(BatchFailure.CANCELLED);
            }
            if (t instanceof WikidataSparqlClient.TruncatedResponseException) {
                return FailureDecision.of(BatchFailure.TRANSIENT);
            }
            if (t instanceof HttpTimeoutException) {
                return FailureDecision.of(BatchFailure.TOO_HEAVY);
            }
            if (t instanceof WikidataSparqlClient.SparqlHttpException http) {
                int status = http.statusCode();
                if (status == 408 || status == 413 || status == 414) {
                    return FailureDecision.of(BatchFailure.TOO_HEAVY);
                }
                if (status == 429 || status == 500 || status == 502
                        || status == 503 || status == 504) {
                    return new FailureDecision(
                            BatchFailure.UNAVAILABLE,
                            Math.max(0, http.retryAfterMillis()));
                }
                return FailureDecision.of(BatchFailure.FATAL);
            }
        }
        return fallback.classify(error);
    }
}
