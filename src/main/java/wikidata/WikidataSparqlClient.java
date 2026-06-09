package wikidata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
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
    private static final String ENDPOINT = "https://query.wikidata.org/sparql";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String userAgent;
    private final ExecutorService executor;

    private Consumer<String> log = s -> {};
    private final AtomicLong querySeq = new AtomicLong();

    private final Set<CompletableFuture<?>> runningQueries =
            ConcurrentHashMap.newKeySet();

    private final List<AbstractButton> runButtons =
            new CopyOnWriteArrayList<>();

    private final List<AbstractButton> cancelButtons =
            new CopyOnWriteArrayList<>();

    private boolean debugJson = false;

    public WikidataSparqlClient(String userAgent) {
        this(userAgent, 2);
    }

    public WikidataSparqlClient(String userAgent, int maxParallelRequests) {
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

    public void registerRunButton(AbstractButton button) {
        if (button == null || runButtons.contains(button)) {
            return;
        }

        runButtons.add(button);
        updateRegisteredButtons();
    }

    public void registerCancelButton(AbstractButton button) {
        if (button == null || cancelButtons.contains(button)) {
            return;
        }

        cancelButtons.add(button);
        updateRegisteredButtons();
    }

    public int runningQueryCount() {
        return runningQueries.size();
    }

    public boolean isQueryRunning() {
        return !runningQueries.isEmpty();
    }

    private void updateRegisteredButtons() {
        boolean running = isQueryRunning();

        SwingUtilities.invokeLater(() -> {
            for (AbstractButton b : runButtons) {
                b.setEnabled(!running);
            }

            for (AbstractButton b : cancelButtons) {
                b.setEnabled(running);
            }
        });
    }

    public void cancelCurrentQuery() {
        for (CompletableFuture<?> f : runningQueries) {
            if (f != null && !f.isDone()) {
                f.cancel(true);
            }
        }

        updateRegisteredButtons();
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

            if (isCancellation(error) || attemptsLeft <= 1) {
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
                                   ENDPOINT
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
        updateRegisteredButtons();

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
            updateRegisteredButtons();
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