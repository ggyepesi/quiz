package wikidata;

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

            // A request that ran into the server timeout will time out
            // again — retry only transient failures.
            if (isCancellation(error)
                    || isTimeout(error)
                    || attemptsLeft <= 1) {
                CompletableFuture<List<WikidataBinding>> failed =
                        new CompletableFuture<>();
                failed.completeExceptionally(error);
                return failed;
            }

            try {
                Thread.sleep(1000L * (4 - attemptsLeft));
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
                            throw new RuntimeException(
                                    "SPARQL HTTP "
                                            + res.statusCode()
                                            + "\n"
                                            + res.body());
                        }

                        return parseJson(res.body());
                    });

        runningQueries.add(future);

        future.whenComplete((r, e) -> {
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
            String head =
                    json == null
                            ? ""
                            : json.substring(0, Math.min(500, json.length()));

            throw new RuntimeException(
                    "Cannot parse SPARQL JSON. Body starts:\n" + head,
                    e);
        }
    }

    @Override
    public void close() {
        cancelCurrentQuery();
        executor.shutdownNow();
    }
}