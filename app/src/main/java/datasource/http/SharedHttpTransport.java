package datasource.http;

import batch.ResponseTimeoutException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/**
 * Shared HTTP/2 transport for datasource clients.
 *
 * <p>One process-wide {@link HttpClient} owns connection pooling and negotiates HTTP/2,
 * so concurrent requests to one origin can be multiplexed instead of each datasource
 * creating an opaque URL connection. Source-specific clients still own status policy,
 * JSON parsing and polite request headers.
 */
public final class SharedHttpTransport {
    private static final SharedHttpTransport STANDARD = new SharedHttpTransport(
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build());

    private final HttpClient client;

    public SharedHttpTransport(HttpClient client) {
        if (client == null) throw new IllegalArgumentException("HTTP client is required");
        this.client = client;
    }

    public static SharedHttpTransport standard() { return STANDARD; }

    public Response get(URI uri, Map<String, String> headers, Duration timeout)
            throws IOException, InterruptedException {
        if (uri == null) throw new IllegalArgumentException("Request URI is required");
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET();
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            request.timeout(timeout);
        }
        if (headers != null) headers.forEach(request::header);

        long started = System.nanoTime();
        AtomicLong headersAt = new AtomicLong();
        AtomicInteger status = new AtomicInteger(-1);
        final HttpHeaders[] responseHeaders = new HttpHeaders[1];
        HttpResponse.BodyHandler<byte[]> body = info -> {
            status.set(info.statusCode());
            responseHeaders[0] = info.headers();
            headersAt.compareAndSet(0, System.nanoTime());
            return HttpResponse.BodySubscribers.ofByteArray();
        };

        try {
            HttpResponse<byte[]> response = client.send(request.build(), body);
            long finished = System.nanoTime();
            byte[] transferred = response.body() == null ? new byte[0] : response.body();
            String encoding = response.headers().firstValue("Content-Encoding").orElse("");
            byte[] decoded = decode(transferred, encoding);
            return new Response(response.statusCode(), response.headers(), decoded,
                    transferred.length, response.version(),
                    millis(started, headersAt.get()), millis(started, finished));
        } catch (HttpConnectTimeoutException timeoutFailure) {
            SocketTimeoutException unavailable = new SocketTimeoutException(
                    "Connect timed out: " + uri);
            unavailable.initCause(timeoutFailure);
            throw unavailable;
        } catch (HttpTimeoutException timeoutFailure) {
            if (headersAt.get() > 0) {
                throw new ResponseTimeoutException(
                        "Response body timed out: " + uri, timeoutFailure);
            }
            SocketTimeoutException unavailable = new SocketTimeoutException(
                    "Response headers timed out: " + uri);
            unavailable.initCause(timeoutFailure);
            throw unavailable;
        } catch (IOException failure) {
            // send() has no response object on failure, but the BodyHandler has already
            // seen the status and headers when a successful response later broke.
            throw new ExchangeIOException(failure.getMessage(), failure, status.get(),
                    responseHeaders[0]);
        }
    }

    public HttpClient.Version preferredVersion() { return client.version(); }

    public record Response(int status, HttpHeaders headers, byte[] body,
            long transferredBytes, HttpClient.Version version,
            long headersMillis, long totalMillis) {
        public String header(String name) {
            return headers == null ? null : headers.firstValue(name).orElse(null);
        }
    }

    /** An I/O failure that remembers whether response headers had already arrived. */
    public static final class ExchangeIOException extends IOException {
        private final int status;
        private final HttpHeaders headers;

        private ExchangeIOException(String message, Throwable cause, int status,
                HttpHeaders headers) {
            super(message, cause);
            this.status = status;
            this.headers = headers;
        }

        public int status() { return status; }
        public String header(String name) {
            return headers == null ? null : headers.firstValue(name).orElse(null);
        }
    }

    static byte[] decode(byte[] body, String contentEncoding) throws IOException {
        if (contentEncoding == null
                || !contentEncoding.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
            return body == null ? new byte[0] : body;
        }
        try (GZIPInputStream gzip = new GZIPInputStream(
                new ByteArrayInputStream(body == null ? new byte[0] : body));
             ByteArrayOutputStream decoded = new ByteArrayOutputStream()) {
            gzip.transferTo(decoded);
            return decoded.toByteArray();
        }
    }

    private static long millis(long started, long finished) {
        return finished <= 0 ? -1 : Math.max(0, (finished - started) / 1_000_000);
    }
}
