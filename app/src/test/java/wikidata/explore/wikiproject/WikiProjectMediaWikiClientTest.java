package wikidata.explore.wikiproject;

import datasource.http.SharedHttpTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client paces requests and owns status policy; the transport owns everything
 * else. The pacing rule is a MINIMUM INTERVAL between request starts, not a sleep
 * before each request — the difference is what one browse click costs.
 */
class WikiProjectMediaWikiClientTest {

    @Test void theFirstRequestOfABurstIsNotTaxed() throws Exception {
        Stub stub = new Stub(200, "{}");
        SharedHttpTransport transport = transportOver(stub);
        WikiProjectMediaWikiClient client =
                new WikiProjectMediaWikiClient("test", 1000, transport);

        long started = System.nanoTime();
        client.get("action=query");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(elapsedMillis < 500,
                "the former blind pre-sleep paid the interval before doing any work, "
                        + "so one browse click paid it once per call; was "
                        + elapsedMillis + " ms");
    }

    @Test void concurrentRequestsPaceAgainstEachOtherRatherThanEachPayingInFull()
            throws Exception {
        Stub stub = new Stub(200, "{}");
        SharedHttpTransport transport = transportOver(stub);
        WikiProjectMediaWikiClient client =
                new WikiProjectMediaWikiClient("test", 100, transport);

        long started = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> client.get("a"));
            var b = pool.submit(() -> client.get("b"));
            var c = pool.submit(() -> client.get("c"));
            a.get(); b.get(); c.get();
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(3, stub.calls.get());
        // Three starts spaced by 100 ms is ~200 ms, not 3 x 100 ms of serial sleeping.
        assertTrue(elapsedMillis < 500, "three paced starts, was " + elapsedMillis + " ms");
    }

    @Test void aRefusalIsNotRetriedAndCarriesTheBody() {
        Stub stub = new Stub(404, "{\"error\":\"missingtitle\"}");
        SharedHttpTransport transport = transportOver(stub);
        WikiProjectMediaWikiClient client =
                new WikiProjectMediaWikiClient("test", 0, transport);

        IOException failure = assertThrows(IOException.class, () -> client.get("q"));
        assertTrue(failure.getMessage().contains("HTTP 404"), failure.getMessage());
        assertTrue(failure.getMessage().contains("missingtitle"),
                "the body says why, so it belongs in the message");
        assertEquals(1, stub.calls.get(), "a 404 is an answer, not a fault");
    }

    @Test void aThrottledRequestIsRetried() throws Exception {
        Stub stub = new Stub(429, "slow down");
        stub.succeedFrom(2, "{\"ok\":true}");
        SharedHttpTransport transport = transportOver(stub);
        WikiProjectMediaWikiClient client =
                new WikiProjectMediaWikiClient("test", 0, transport);

        assertEquals("{\"ok\":true}", client.get("q"));
        assertEquals(2, stub.calls.get(), "429 is retried rather than surfaced raw");
    }

    /** A transport over a stubbed {@link HttpClient}: the real SharedHttpTransport
     *  and the real client status policy run, nothing reaches the network. */
    private static SharedHttpTransport transportOver(Stub stub) {
        return new SharedHttpTransport(stub);
    }

    private static final class Stub extends HttpClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final int status;
        private final String body;
        private int succeedFromCall = Integer.MAX_VALUE;
        private String successBody = "";

        private Stub(int status, String body) {
            this.status = status;
            this.body = body;
        }

        private void succeedFrom(int call, String body) {
            this.succeedFromCall = call;
            this.successBody = body;
        }

        @Override @SuppressWarnings("unchecked")
        public <T> java.net.http.HttpResponse<T> send(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> handler) {
            int call = calls.incrementAndGet();
            boolean ok = call >= succeedFromCall;
            return (java.net.http.HttpResponse<T>) new StubResponse(
                    ok ? 200 : status,
                    (ok ? successBody : body).getBytes(StandardCharsets.UTF_8),
                    request.uri());
        }

        @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() {
            return java.util.Optional.empty(); }
        @Override public java.util.Optional<Duration> connectTimeout() {
            return java.util.Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public java.util.Optional<java.net.ProxySelector> proxy() {
            return java.util.Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() {
            try { return javax.net.ssl.SSLContext.getDefault(); }
            catch (Exception e) { throw new IllegalStateException(e); } }
        @Override public javax.net.ssl.SSLParameters sslParameters() {
            return new javax.net.ssl.SSLParameters(); }
        @Override public java.util.Optional<java.net.Authenticator> authenticator() {
            return java.util.Optional.empty(); }
        @Override public Version version() { return Version.HTTP_2; }
        @Override public java.util.Optional<java.util.concurrent.Executor> executor() {
            return java.util.Optional.empty(); }
        @Override public <T> java.util.concurrent.CompletableFuture<
                java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> handler) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    send(request, handler)); }
        @Override public <T> java.util.concurrent.CompletableFuture<
                java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> handler,
                java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler); }
    }

    private record StubResponse(int code, byte[] payload, URI uri)
            implements java.net.http.HttpResponse<byte[]> {
        @Override public int statusCode() { return code; }
        @Override public java.net.http.HttpRequest request() { return null; }
        @Override public java.util.Optional<java.net.http.HttpResponse<byte[]>>
                previousResponse() { return java.util.Optional.empty(); }
        @Override public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("Retry-After", List.of("0")), (a, b) -> true); }
        @Override public byte[] body() { return payload; }
        @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty(); }
        @Override public URI uri() { return uri; }
        @Override public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2; }
    }
}
