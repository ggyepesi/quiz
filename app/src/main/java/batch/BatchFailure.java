package batch;

/**
 * What a failed attempt means to the executor. Domain and transport exceptions are mapped
 * to these meanings by an injected {@link FailureClassifier}; the generic batch package
 * therefore does not depend on Wikidata exception classes or class-name conventions.
 */
public enum BatchFailure {

    /** Worth trying again unchanged: the request did not really run to completion.
     *  Measured case — WDQS closing a response stream early (HTTP 200, body cut on a
     *  buffer boundary); the identical query then succeeded in 1-2s. */
    TRANSIENT,

    /** The endpoint or network is temporarily unavailable. Retry unchanged, but never
     * split after the retry budget: multiplying an outage into smaller requests only
     * increases load. */
    UNAVAILABLE,

    /** The request ran and could not finish, and will not finish next time either.
     *  Retrying verbatim only spends the same wall-clock again; the way forward is a
     *  SMALLER request. */
    TOO_HEAVY,

    /** The user stopped us. Never retried, never split, propagated immediately. */
    CANCELLED,

    /** Anything we do not understand. Never retried or split. */
    FATAL
}
