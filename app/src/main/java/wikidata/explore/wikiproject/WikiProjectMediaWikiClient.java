package wikidata.explore.wikiproject;

import domain.datasource.http.SharedHttpTransport;
import objectview.utils.RetryAfter;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Reads the English Wikipedia MediaWiki API.
 *
 * <p>Requests go through {@link SharedHttpTransport}, so this client inherits the
 * connection pooling, HTTP/2 negotiation, redirect policy, gzip and — the reason it
 * matters most here — the connect/headers/body timeout distinction that a bare
 * {@code HttpURLConnection} had none of. An interactive browser on a stalled socket
 * used to have no ceiling at all.
 *
 * <p>Status policy stays here, as the transport intends: a 429 or a 5xx is retried,
 * honouring {@code Retry-After} when the server states one.
 */
public class WikiProjectMediaWikiClient {
    private static final String ENDPOINT =
            "https://en.wikipedia.org/w/api.php";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int ATTEMPTS = 3;

    /** Pacing for a bulk walk, where requests are issued back to back. */
    public static final long BULK_INTERVAL_MILLIS = 1000;
    /** Pacing for a browser, where a burst is what one deliberate click costs. */
    public static final long INTERACTIVE_INTERVAL_MILLIS = 200;

    private final String userAgent;
    private final long minIntervalMillis;
    private final SharedHttpTransport transport;
    // Rate is held per client, so the concurrent requests one browse issues pace
    // against each other rather than each paying the interval in full.
    private final Object pace = new Object();
    private long nextAllowedNanos;
    private boolean debug;

    public WikiProjectMediaWikiClient() {
        this("QuizProject/1.0 (ggyepesi@gmail.com)", BULK_INTERVAL_MILLIS);
    }

    public WikiProjectMediaWikiClient(String userAgent, long minIntervalMillis) {
        this(userAgent, minIntervalMillis, SharedHttpTransport.standard());
    }

    public WikiProjectMediaWikiClient(
            String userAgent, long minIntervalMillis, SharedHttpTransport transport) {
        this.userAgent = userAgent == null || userAgent.isBlank()
                ? "QuizProject/1.0"
                : userAgent;
        this.minIntervalMillis = Math.max(0, minIntervalMillis);
        this.transport = transport == null ? SharedHttpTransport.standard() : transport;
    }

    /** A client paced for interactive browsing rather than a bulk walk. */
    public static WikiProjectMediaWikiClient interactive() {
        return new WikiProjectMediaWikiClient(
                "QuizProject/1.0 (ggyepesi@gmail.com)", INTERACTIVE_INTERVAL_MILLIS);
    }

    public void debug(boolean debug) {
        this.debug = debug;
    }

    public String get(String queryString) throws Exception {
        String url = url(queryString);
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            SharedHttpTransport.Response response;
            try {
                response = requestOnce(queryString);
            } catch (IOException transportFault) {
                // Only a transport fault reaches here; a refusal this method decides
                // on is returned by the code path below, never round-tripped through
                // catch, so the two are told apart by control flow and not by message.
                if (attempt == ATTEMPTS) throw transportFault;
                lastFailure = transportFault;
                backOff(attempt * 1000L);
                continue;
            }

            int code = response.status();
            String body = new String(response.body(), StandardCharsets.UTF_8);
            if (code >= 200 && code < 300) {
                return body;
            }
            if (!worthRetrying(code) || attempt == ATTEMPTS) {
                throw new IOException("HTTP " + code + " from MediaWiki API: " + body);
            }
            lastFailure = new IOException("HTTP " + code + " from MediaWiki API");
            backOff(RetryAfter.millis(response.header("Retry-After"), attempt * 1000L));
        }
        throw lastFailure == null
                ? new IOException("MediaWiki API request failed: " + url)
                : lastFailure;
    }

    /**
     * Issues exactly one request. Observable batch callers own retries so every
     * attempt appears in their shared progress log instead of being hidden here.
     */
    public String getOnce(String queryString) throws Exception {
        String requestUrl = url(queryString);
        SharedHttpTransport.Response response = requestOnce(queryString);
        int code = response.status();
        String body = new String(response.body(), StandardCharsets.UTF_8);
        if (code >= 200 && code < 300) return body;
        objectview.utils.UrlOpener.HttpStatusException status =
                new objectview.utils.UrlOpener.HttpStatusException(
                        code, URI.create(requestUrl).toURL());
        status.initCause(new IOException("MediaWiki API response: " + body));
        throw status;
    }

    private SharedHttpTransport.Response requestOnce(String queryString) throws Exception {
        if (debug) {
            System.out.println("WikiProjectMediaWikiClient query |"
                    + queryString + "|");
        }
        throttle();
        String requestUrl = url(queryString);
        SharedHttpTransport.Response response = transport.get(
                URI.create(requestUrl),
                Map.of("User-Agent", userAgent,
                        "Accept", "application/json",
                        "Accept-Encoding", "gzip"),
                REQUEST_TIMEOUT);
        if (debug) {
            System.out.println("WikiProjectMediaWikiClient responseCode "
                    + response.status() + " for " + requestUrl);
        }
        return response;
    }

    // Wait only the remainder of the interval since the previous request STARTED.
    // The former blind pre-sleep taxed even the first request of a burst, so one
    // browse click paid the interval once per call before any work happened.
    private void throttle() throws InterruptedException {
        if (minIntervalMillis <= 0) return;
        long waitNanos;
        synchronized (pace) {
            long now = System.nanoTime();
            long start = Math.max(now, nextAllowedNanos);
            nextAllowedNanos = start + minIntervalMillis * 1_000_000L;
            waitNanos = start - now;
        }
        if (waitNanos > 0) {
            Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
        }
    }

    private static boolean worthRetrying(int status) {
        return status == 429 || status >= 500;
    }

    private static void backOff(long millis) throws InterruptedException {
        if (millis > 0) Thread.sleep(Math.min(millis, 30_000L));
    }

    /** The full GET URL for a query string — so a request can be logged as a
     *  runnable link (open it in a browser to see the JSON). */
    public static String url(String queryString) {
        return ENDPOINT + "?" + (queryString == null ? "" : queryString);
    }

    public static String enc(String s) {
        return URLEncoder.encode(
                s == null ? "" : s,
                StandardCharsets.UTF_8);
    }
}
