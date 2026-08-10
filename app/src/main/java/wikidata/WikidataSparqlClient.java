package wikidata;

import objectview.utils.RetryAfter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class WikidataSparqlClient implements AutoCloseable {
    public static final String WIKIDATA_ENDPOINT = "https://query.wikidata.org/sparql";
    public static final String DBPEDIA_ENDPOINT  = "https://dbpedia.org/sparql";

    private final String endpoint;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String userAgent;
    private final ExecutorService executor;

    // Caps in-flight requests regardless of how many threads call query() — so
    // fanning qualifier-load batches out concurrently can't outrun WDQS. A permit
    // is taken before each send and released when it completes.
    private final Semaphore rateLimiter;

    // Optional courtesy spacing between request STARTS (0 = off). Complements the
    // permit gate: the semaphore bounds concurrency, this bounds burst rate.
    private volatile long minRequestSpacingMillis = 0;
    private final Object spacingLock = new Object();
    private long lastRequestStart = 0;

    private Consumer<String> log = s -> {};
    private final AtomicLong querySeq = new AtomicLong();

    private final Set<CompletableFuture<?>> runningQueries =
            ConcurrentHashMap.newKeySet();

    private boolean debugJson = false;

    public WikidataSparqlClient(String userAgent) {
        this(userAgent, 2);
    }

    public WikidataSparqlClient(String userAgent, int maxParallelRequests) {
        this(userAgent, maxParallelRequests, WIKIDATA_ENDPOINT);
    }

    /** Point the client at a different SPARQL endpoint (e.g. DBPEDIA_ENDPOINT). */
    public WikidataSparqlClient(
            String userAgent, int maxParallelRequests, String endpoint) {
        this.endpoint = endpoint == null || endpoint.isBlank()
                ? WIKIDATA_ENDPOINT : endpoint;
        this.userAgent =
                userAgent == null || userAgent.isBlank()
                        ? "QuizBot/1.0"
                        : userAgent;

        this.executor =
                Executors.newFixedThreadPool(Math.max(1, maxParallelRequests));

        this.rateLimiter =
                new Semaphore(Math.max(1, maxParallelRequests), true);

        this.http =
                HttpClient.newBuilder()
                          .executor(executor)
                          .connectTimeout(Duration.ofSeconds(30))
                          .build();
    }

    public void log(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
    }

    public void debugJson(boolean debugJson) {
        this.debugJson = debugJson;
    }

    /** Minimum spacing between request STARTS (courtesy throttle); 0 disables it. */
    public void minRequestSpacingMillis(long millis) {
        this.minRequestSpacingMillis = Math.max(0, millis);
    }

    public int runningQueryCount() {
        return runningQueries.size();
    }

    public boolean isQueryRunning() {
        return !runningQueries.isEmpty();
    }

    public void cancelCurrentQuery() {
        for (CompletableFuture<?> f : runningQueries) {
            if (f != null && !f.isDone()) {
                f.cancel(true);
            }
        }
    }

    public List<WikidataBinding> query(String sparql) throws Exception {
        try {
            return queryAsync(sparql).get();
        } catch (CancellationException e) {
            throw new InterruptedException("SPARQL query was cancelled.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();

            if (cause instanceof CancellationException) {
                throw new InterruptedException("SPARQL query was cancelled.");
            }
            if (cause instanceof Exception ex) {
                throw ex;
            }

            throw new RuntimeException(cause);
        }
    }

    public CompletableFuture<List<WikidataBinding>> queryAsync(String sparql) {
        System.out.println("SPARQL " + sparql);
        return queryAsyncWithRetry(sparql, 3);
    }

    private CompletableFuture<List<WikidataBinding>> queryAsyncWithRetry(
            String sparql,
            int attemptsLeft) {

        return sendOnceAsync(sparql).handle((result, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(result);
            }

            if (!shouldRetry(error, attemptsLeft)) {
                CompletableFuture<List<WikidataBinding>> failed =
                        new CompletableFuture<>();
                failed.completeExceptionally(error);
                return failed;
            }

            try {
                // Honor a 429's Retry-After; otherwise linear backoff.
                Thread.sleep(backoffMillis(error, attemptsLeft));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return queryAsyncWithRetry(sparql, attemptsLeft - 1);
        }).thenCompose(f -> f);
    }

    private CompletableFuture<List<WikidataBinding>> sendOnceAsync(String sparql) {
        long id = querySeq.incrementAndGet();
        long started = System.nanoTime();

        log.accept("\n[SPARQL " + id + "] START\n"
                           + sparql
                           + "\n");

        try {
            acquirePermit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CompletableFuture<List<WikidataBinding>> failed =
                    new CompletableFuture<>();
            failed.completeExceptionally(
                    new CancellationException("Interrupted before SPARQL send."));
            return failed;
        }

        String encoded =
                URLEncoder.encode(sparql, StandardCharsets.UTF_8);

        HttpRequest req =
                HttpRequest.newBuilder()
                           .uri(URI.create(
                                   endpoint
                                           + "?query="
                                           + encoded
                                           + "&format=json"))
                           .header(
                                   "Accept",
                                   "application/sparql-results+json")
                           .header("User-Agent", userAgent)
                           .timeout(Duration.ofSeconds(60))
                           .GET()
                           .build();

        CompletableFuture<List<WikidataBinding>> future =
                http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenApply(res -> {
                        if (res.statusCode() != 200) {
                            throw new SparqlHttpException(
                                    res.statusCode(),
                                    RetryAfter.millis(
                                            res.headers()
                                               .firstValue("Retry-After")
                                               .orElse(null),
                                            -1),
                                    res.body());
                        }

                        return parseJson(res.body());
                    });

        runningQueries.add(future);

        future.whenComplete((r, e) -> {
            rateLimiter.release();
            long ms = (System.nanoTime() - started) / 1_000_000;

            if (e == null) {
                int rows = r == null ? -1 : r.size();
                log.accept("[SPARQL " + id + "] OK rows="
                                   + rows + " timeMs=" + ms + "\n");
            } else if (isCancellation(e)) {
                log.accept("[SPARQL " + id + "] CANCELLED timeMs="
                                   + ms + "\n");
            } else {
                log.accept("[SPARQL " + id + "] ERROR "
                                   + e.getClass().getSimpleName()
                                   + ": "
                                   + e.getMessage()
                                   + " timeMs=" + ms + "\n");
            }
            runningQueries.remove(future);
        });

        return future;
    }

    private static boolean isCancellation(Throwable t) {
        while (t != null) {
            if (t instanceof CancellationException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static boolean isTimeout(Throwable t) {
        while (t != null) {
            if (t instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private void acquirePermit() throws InterruptedException {
        rateLimiter.acquire();

        long spacing = minRequestSpacingMillis;
        if (spacing > 0) {
            synchronized (spacingLock) {
                long wait = spacing
                        - (System.currentTimeMillis() - lastRequestStart);
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                lastRequestStart = System.currentTimeMillis();
            }
        }
    }

    /**
     * Retry transient failures only: a server timeout would just time out again,
     * a cancellation is intentional, and an HTTP status the server won't recover
     * from (a 4xx other than 429) is a client error not worth repeating. A 429 or
     * 5xx, or a bare network error, is retried until attempts run out.
     */
    /**
     * A truncated partial-200 IS retried. Measured: a residual field batch (100
     * members, two OPTIONAL scalars, label SERVICE) truncated mid-run and then
     * returned 317 rows in 1-2s, twice, byte-identical — the truncation was
     * transient pressure on WDQS, not a query that cannot finish. The bodies stop
     * on buffer boundaries (32 KiB, 448 KiB observed), i.e. the response stream is
     * closed early rather than the query timing out.
     *
     * <p>A query that genuinely cannot finish fails the same way every attempt and
     * simply exhausts the budget — costing two extra requests, which is the right
     * price for telling the two apart. Narrowing such a query is the caller's job,
     * not the transport's: a smaller query is a different query.
     *
     * <p>A client-side {@link java.net.http.HttpTimeoutException} stays
     * non-retryable — there the 60s wall was actually hit, so a verbatim re-issue
     * burns another 60s for the same result.
     */
    static boolean shouldRetry(Throwable error, int attemptsLeft) {
        if (attemptsLeft <= 1
                || isCancellation(error)
                || isTimeout(error)) {
            return false;
        }
        if (isTruncated(error)) {
            return true;
        }
        SparqlHttpException http = httpError(error);
        return http == null || isRetryableStatus(http.statusCode());
    }

    private static boolean isTruncated(Throwable t) {
        while (t != null) {
            if (t instanceof TruncatedResponseException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** The wait before the next attempt: a 429's Retry-After if given, else a
     *  linear 1s/2s/3s… backoff by attempt number. */
    static long backoffMillis(Throwable error, int attemptsLeft) {
        SparqlHttpException http = httpError(error);
        if (http != null && http.retryAfterMillis() > 0) {
            return http.retryAfterMillis();
        }
        return 1000L * (4 - attemptsLeft);
    }

    static boolean isRetryableStatus(int status) {
        return status == 429
                || status == 500
                || status == 502
                || status == 503
                || status == 504;
    }

    private static SparqlHttpException httpError(Throwable t) {
        while (t != null) {
            if (t instanceof SparqlHttpException http) {
                return http;
            }
            t = t.getCause();
        }
        return null;
    }

    /** Carries the HTTP status and any Retry-After so the retry loop can decide
     *  whether — and how long — to wait, instead of parsing an error string. */
    public static final class SparqlHttpException extends RuntimeException {
        private final int statusCode;
        private final long retryAfterMillis;

        public SparqlHttpException(
                int statusCode, long retryAfterMillis, String body) {
            super("SPARQL HTTP " + statusCode
                    + (body == null || body.isBlank() ? "" : "\n" + body));
            this.statusCode = statusCode;
            this.retryAfterMillis = retryAfterMillis;
        }

        public int statusCode() {
            return statusCode;
        }

        /** Millis the server asked us to wait, or a negative value if it didn't. */
        public long retryAfterMillis() {
            return retryAfterMillis;
        }
    }

    private List<WikidataBinding> parseJson(String json) {
        if (debugJson) {
            System.out.println(json);
        }

        try {
            JsonNode root = mapper.readTree(json);
            JsonNode bindings = root.path("results").path("bindings");

            System.out.println("Json " + bindings.size() + " nodes");

            List<WikidataBinding> out = new ArrayList<>();

            for (JsonNode rowNode : bindings) {
                Map<String, String> row = new LinkedHashMap<>();

                Iterator<String> names = rowNode.fieldNames();

                while (names.hasNext()) {
                    String name = names.next();
                    String value =
                            rowNode.path(name).path("value").asText(null);

                    if (value != null) {
                        row.put(name, value);
                    }
                }

                out.add(new WikidataBinding(row));
            }

            return out;
        } catch (Exception e) {
            int len = json == null ? 0 : json.length();
            String head = json == null
                    ? ""
                    : json.substring(0, Math.min(400, len));
            String tail = json == null || len <= 400
                    ? ""
                    : "\n  … [last 200 chars] …\n"
                            + json.substring(Math.max(0, len - 200));
            // A truncated "partial 200" (WDQS returns HTTP 200 with a body cut off
            // mid-JSON under load) doesn't close its root object. Flag it so the
            // failure reads as transient (the retry re-issues it) rather than a
            // query bug.
            boolean truncated = json == null
                    || !json.stripTrailing().endsWith("}");
            String msg = "Cannot parse SPARQL JSON (" + len + " chars"
                    + (truncated
                            ? "; body does not end in '}' — a TRUNCATED partial-200"
                                    + " from WDQS, transient: retry"
                            : "")
                    + "). Body starts:\n" + head + tail;

            // Mirror to the console so it's copyable even when the UI dialog isn't.
            System.err.println("[SPARQL parse] " + msg);

            // Distinguished from an ordinary parse failure so the retry policy can
            // re-issue it (see shouldRetry): a truncated body is usually transient
            // pressure on WDQS, not an unfinishable query.
            throw truncated
                    ? new TruncatedResponseException(msg, e)
                    : new RuntimeException(msg, e);
        }
    }

    /** A partial/truncated 200 response body: HTTP 200 whose JSON stops mid-object,
     *  because WDQS closed the response stream early. Retried — see
     *  {@link #shouldRetry} for the evidence and for the case it does not cover. */
    public static final class TruncatedResponseException
            extends RuntimeException {
        public TruncatedResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Override
    public void close() {
        cancelCurrentQuery();
        executor.shutdownNow();
    }
}