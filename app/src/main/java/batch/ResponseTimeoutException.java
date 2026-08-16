package batch;

/**
 * A response was accepted, but its body did not arrive in time. Unlike a connection
 * timeout, repeating the same oversized request is unlikely to help; a batch executor
 * should reduce the request before trying again.
 */
public final class ResponseTimeoutException extends java.net.SocketTimeoutException {
    public ResponseTimeoutException(String message, Throwable cause) {
        super(message);
        if (cause != null) initCause(cause);
    }
}
