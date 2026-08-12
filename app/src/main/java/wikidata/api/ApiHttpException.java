package wikidata.api;

import java.io.IOException;

/**
 * An action-API request the server refused, carrying what the retry needs to decide:
 * the status, and the wait the server asked for.
 *
 * <p>The API client used to throw a bare {@link IOException} for every failure, so its
 * retry could not tell a 429 from a 404 and treated both the same — three attempts,
 * half a second apart. A real throttling window is far longer than that: 54 consecutive
 * label batches of a 20,000-member run were refused with 429 and gave up inside three
 * seconds, leaving 2,700 references named by their QID.
 */
public class ApiHttpException extends IOException {

    private final int status;
    private final long retryAfterMillis;

    public ApiHttpException(
            int status, long retryAfterMillis, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryAfterMillis = retryAfterMillis;
    }

    public int status() {
        return status;
    }

    /** The server's {@code Retry-After} in milliseconds, or -1 when it did not say. */
    public long retryAfterMillis() {
        return retryAfterMillis;
    }
}
