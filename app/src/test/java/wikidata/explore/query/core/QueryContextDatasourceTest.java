package wikidata.explore.query.core;

import org.junit.jupiter.api.Test;
import wikidata.WikidataSparqlClient;
import work.QueryContext;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryContextDatasourceTest {

    @Test
    void datasourceSelectionIsExplicitAndMissingBindingsFailImmediately() {
        try (WikidataSparqlClient wikidata = new WikidataSparqlClient("test", 1);
             WikidataSparqlClient dbpedia = new WikidataSparqlClient(
                     "test", 1, Datasource.DBPEDIA.endpoint())) {
            QueryContext context = WikidataAccess.of(wikidata, null)
                    .with(Datasource.DBPEDIA, dbpedia).bind();

            assertSame(wikidata, WikidataAccess.sparql(context, Datasource.WIKIDATA));
            assertSame(dbpedia, WikidataAccess.sparql(context, Datasource.DBPEDIA));
        }

        QueryContext noBinding = WikidataAccess.of(null, null).bind();
        assertThrows(IllegalStateException.class,
                () -> WikidataAccess.sparql(noBinding, Datasource.WIKIDATA));
    }

    /**
     * #116. Each client writes its own {@code START} / {@code OK … timeMs=} lines, and
     * the sink defaulted to a discarded consumer that nothing ever replaced — so a run
     * reporting 385 s in one phase could not name the query that spent it. A run says
     * once where requests report, and EVERY endpoint it can reach obeys: wiring only
     * the client an acquisition happens to hold is how DBpedia became the sole endpoint
     * ever logged, and then stopped being logged when that acquisition took its client
     * from the context instead of building one.
     */
    @Test
    void everyBoundEndpointReportsItsRequestsIntoTheRunsLog() {
        try (RecordingClient wikidata = new RecordingClient(Datasource.WIKIDATA.endpoint());
             RecordingClient dbpedia = new RecordingClient(Datasource.DBPEDIA.endpoint())) {
            QueryContext context = WikidataAccess.of(wikidata, null)
                    .with(Datasource.DBPEDIA, dbpedia).bind();
            List<String> lines = new ArrayList<>();

            try (WikidataAccess.RequestLogs ignored =
                    WikidataAccess.logRequests(context, lines::add)) {
                assertNotNull(wikidata.sink, "the default endpoint reports");
                assertNotNull(dbpedia.sink, "and so does every other one bound to the run");
                wikidata.sink.accept("request");
                dbpedia.sink.accept("request");
                assertEquals(List.of("[WIKIDATA] request", "[DBPEDIA] request"), lines,
                        "endpoint identity remains visible when query ids overlap");
            }
            assertTrue(wikidata.closed && dbpedia.closed,
                    "a finished run detaches both endpoint sinks");
        }
    }

    /**
     * A client's messages open and close with blank lines — START prints the query
     * beneath itself — and prefixing the block put the endpoint's name alone above the
     * event and left an orphan below it. The label went missing from the one line it
     * exists to identify, and only two interleaved endpoints would have shown it.
     */
    @Test void theEndpointNamesTheLineThatCarriesTheEvent() {
        try (RecordingClient wikidata = new RecordingClient(Datasource.WIKIDATA.endpoint())) {
            QueryContext context = WikidataAccess.of(wikidata, null).bind();
            List<String> lines = new ArrayList<>();

            try (WikidataAccess.RequestLogs ignored =
                    WikidataAccess.logRequests(context, lines::add)) {
                wikidata.sink.accept("\n[SPARQL 4] START\nSELECT ?s WHERE { }\n");
                wikidata.sink.accept("[SPARQL 4] OK rows=2 timeMs=7\n");
            }

            assertTrue(lines.getFirst().startsWith("[WIKIDATA] [SPARQL 4] START"),
                    "the label belongs on the event, not above it: " + lines.getFirst());
            assertEquals(2, lines.size(), "and no orphan per message: " + lines);
            assertTrue(lines.get(1).startsWith("[WIKIDATA] [SPARQL 4] OK"));
        }
    }

    /** Records the sink it is given; {@code log} is how a run addresses a client. */
    private static final class RecordingClient extends WikidataSparqlClient {
        private java.util.function.Consumer<String> sink;
        private boolean closed;
        private RecordingClient(String endpoint) { super("test", 1, endpoint); }
        @Override public AutoCloseable requestLog(
                java.util.function.Consumer<String> log) {
            this.sink = log;
            AutoCloseable scope = super.requestLog(log);
            return () -> { scope.close(); closed = true; };
        }
    }

    /** A context that reaches no source at all is a legitimate context — it is what a
     *  Wikipedia read or a pure process step runs on — so asking it for Wikidata access
     *  must say that plainly rather than hand back a half-configured client. */
    @Test
    void aContextWithNoWikidataAccessSaysSoInsteadOfPretending() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> WikidataAccess.sparql(new QueryContext(), Datasource.WIKIDATA));

        assertTrue(refused.getMessage().contains("WikidataAccess"), refused.getMessage());
    }

    @Test
    void factoryOwnsItsSecondaryClientsAndCannotMintContextsAfterClose() {
        try (WikidataSparqlClient wikidata = new WikidataSparqlClient("test", 1)) {
            QueryFactory factory = new QueryFactory(wikidata, null, "test");
            QueryContext context = factory.newContext();
            assertSame(wikidata, WikidataAccess.sparql(context, Datasource.WIKIDATA));
            factory.close();
            factory.close(); // ownership cleanup is deliberately idempotent
            assertThrows(IllegalStateException.class, factory::newContext);
        }
    }
}
