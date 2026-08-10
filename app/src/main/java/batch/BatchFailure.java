package batch;

import java.util.concurrent.CancellationException;

/**
 * What a failure MEANS, decided in one place.
 *
 * <p>The work below the executor reaches three different HTTP paths, each with its own
 * retry policy and its own exception vocabulary — the SPARQL client, the wbgetentities
 * client and the Wikimedia URL opener. Without a single classifier, "retry then split"
 * would quietly behave differently depending on which one happened to serve the request.
 * The transports keep their own transport-level retries; this decides what to do once
 * they have given up.
 */
public enum BatchFailure {

    /** Worth trying again unchanged: the request did not really run to completion.
     *  Measured case — WDQS closing a response stream early (HTTP 200, body cut on a
     *  buffer boundary); the identical query then succeeded in 1-2s. */
    TRANSIENT,

    /** The request ran and could not finish, and will not finish next time either.
     *  Retrying verbatim only spends the same wall-clock again; the way forward is a
     *  SMALLER request. */
    TOO_HEAVY,

    /** The user stopped us. Never retried, never split, propagated immediately. */
    CANCELLED,

    /** Anything we do not understand. Bounded retries, then the run fails — a failure we
     *  cannot classify is not a failure we should paper over. */
    FATAL;

    /**
     * Classifies by walking the cause chain, because every layer between here and the
     * socket wraps ({@code CompletionException}, {@code ExecutionException}, …).
     *
     * <p>Note what is deliberately NOT here: HTTP 429/5xx. By the time such a failure
     * reaches the executor the SPARQL client has already retried it three times with
     * {@code Retry-After} honoured, so a further attempt at the same size is not the
     * useful move — narrowing is. It classifies as {@link #TOO_HEAVY}.
     */
    public static BatchFailure classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof CancellationException) {
                return CANCELLED;
            }
            String name = t.getClass().getSimpleName();
            // Matched by name so `batch` stays free of wikidata/java.net.http imports —
            // it must remain usable by any caller, not just the ones that own these types.
            if (name.equals("TruncatedResponseException")) {
                return TRANSIENT;
            }
            if (name.equals("HttpTimeoutException")) {
                // The 60s wall was actually hit: a verbatim re-issue burns another 60s
                // for the same answer. Split straight away.
                return TOO_HEAVY;
            }
            if (name.equals("SparqlHttpException")) {
                return TOO_HEAVY;
            }
        }
        return Thread.currentThread().isInterrupted() ? CANCELLED : FATAL;
    }
}
