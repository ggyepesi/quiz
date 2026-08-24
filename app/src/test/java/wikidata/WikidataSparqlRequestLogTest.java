package wikidata;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a request's own log lines go, and for how long.
 *
 * <p>Every query writes {@code START} and then {@code OK … timeMs=}; for a long time the
 * sink was a discarded consumer and nothing replaced it, so a run that spent minutes in
 * one phase could not name the query that spent them.
 */
class WikidataSparqlRequestLogTest {

    /**
     * A query is very often issued from somewhere other than the thread that opened the
     * scope: the edge loader runs one query per parent on a pool, and those are the
     * requests most worth timing. A thread-local registration reported the first kind
     * and silently dropped the second — 94 per-parent queries on the constellations
     * domain, inside a phase that took four minutes.
     */
    @Test void aRequestIssuedFromAWorkerThreadReportsToTheRunThatOpenedTheScope()
            throws Exception {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (WikidataSparqlClient client = unreachable()) {
            try (AutoCloseable scope = client.requestLog(lines::add)) {
                // START is written before the send goes asynchronous, so this needs no
                // network and no retry wait.
                pool.submit(() -> client.queryAsync("SELECT ?worker")).get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(lines.stream().anyMatch(line -> line.contains("SELECT ?worker")),
                "a pooled request reports into the run's log: " + lines);
    }

    /** A scope that has closed stops claiming new requests. What it cannot do is silence
     *  one already in flight — that request captured its sink before going asynchronous,
     *  which is what keeps a finished run's completion out of the next run's log. */
    @Test void aClosedScopeNoLongerClaimsRequests() throws Exception {
        List<String> during = new ArrayList<>();
        List<String> after = new ArrayList<>();
        try (WikidataSparqlClient client = unreachable()) {
            try (AutoCloseable scope = client.requestLog(during::add)) {
                client.queryAsync("SELECT ?inside");
            }
            client.requestLog(after::add);
            client.queryAsync("SELECT ?outside");
        }

        assertTrue(during.stream().anyMatch(line -> line.contains("SELECT ?inside")));
        assertEquals(List.of(), during.stream()
                        .filter(line -> line.contains("SELECT ?outside")).toList(),
                "the finished run's log does not collect the next run's requests");
        assertTrue(after.stream().anyMatch(line -> line.contains("SELECT ?outside")));
    }

    private static WikidataSparqlClient unreachable() {
        return new WikidataSparqlClient("test", 1, "https://example.invalid/sparql");
    }
}
