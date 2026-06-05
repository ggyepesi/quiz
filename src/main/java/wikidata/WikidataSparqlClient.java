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

public class WikidataSparqlClient implements AutoCloseable{
    private static final String ENDPOINT = "https://query.wikidata.org/sparql";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String userAgent;
    private final ExecutorService executor;
    private volatile CompletableFuture<?> currentQuery;
    private boolean debugJson = false;

    public void debugJson(boolean debugJson) {
        this.debugJson = debugJson;
    }

    public WikidataSparqlClient(String userAgent) {
        this(userAgent, 2);
    }

    public WikidataSparqlClient(String userAgent, int maxParallelRequests) {
        this.userAgent = userAgent == null || userAgent.isBlank()
                ? "QuizBot/1.0"
                : userAgent;

        this.executor = Executors.newFixedThreadPool(
                Math.max(1, maxParallelRequests));

        this.http = HttpClient.newBuilder()
                              .executor(executor)
                              .connectTimeout(Duration.ofSeconds(30))
                              .build();
    }

    public void cancelCurrentQuery() {
        CompletableFuture<?> f = currentQuery;

        if (f != null && !f.isDone()) {
            f.cancel(true);
        }
    }

    public List<WikidataBinding> query(String sparql) throws Exception {
        try {
            return queryAsync(sparql).get();
        } catch (CancellationException e) {
            throw new InterruptedException("SPARQL query was cancelled.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();

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

            if (attemptsLeft <= 1) {
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
        String encoded = URLEncoder.encode(sparql, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                                     .uri(URI.create(ENDPOINT + "?query=" + encoded + "&format=json"))
                                     .header("Accept", "application/sparql-results+json")
                                     .header("User-Agent", userAgent)
                                     .timeout(Duration.ofSeconds(60))
                                     .GET()
                                     .build();

        CompletableFuture<List<WikidataBinding>> future =
                http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenApply(res -> {
                        if (res.statusCode() != 200) {
                            throw new RuntimeException(
                                    "SPARQL HTTP " + res.statusCode()
                                            + "\n" + res.body());
                        }

                        return parseJson(res.body());
                    });

        currentQuery = future;

        future.whenComplete((r, e) -> {
            if (currentQuery == future) {
                currentQuery = null;
            }
        });

        return future;
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
                    String value = rowNode.path(name).path("value").asText(null);

                    if (value != null) {
                        row.put(name, value);
                    }
                }

                out.add(new WikidataBinding(row));
            }

            return out;
        } catch (Exception e) {
            String head = json == null ? "" : json.substring(0, Math.min(500, json.length()));
            throw new RuntimeException("Cannot parse SPARQL JSON. Body starts:\n" + head, e);
        }
    }

    @Override
    public void close() throws Exception {
        executor.shutdownNow();
    }
}