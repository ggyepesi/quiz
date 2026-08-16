package batch;

import java.io.IOException;

/** A successful response started, but its body ended through a transport failure. */
public final class ResponseInterruptedException extends IOException {
    public ResponseInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
