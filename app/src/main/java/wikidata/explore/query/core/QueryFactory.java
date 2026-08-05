package wikidata.explore.query.core;

import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;

import java.util.EnumMap;
import java.util.Map;

/**
 * The single authority on which {@link Datasource}s exist and how each maps to a
 * live endpoint client. Queries only carry a datasource <em>tag</em>
 * ({@link Query#datasource()}); this factory stamps every datasource's client
 * into the {@link QueryContext}s it mints, and the context routes each query to
 * the endpoint its tag names. Callers therefore never hand-wire — nor forget to
 * bind — a datasource: they take a fully-wired context from {@link #newContext()}.
 *
 * <p>The primary WIKIDATA (WDQS) client is <em>adopted</em> from the caller (it is
 * often used for other things too, e.g. cancel wiring), so the factory does not
 * close it. The datasource-dependent clients the factory creates (DBpedia) it
 * owns and closes in {@link #close()}. Adding a new datasource is a one-line
 * change here — no caller has to learn about it.
 */
public final class QueryFactory implements AutoCloseable {

    private final WikidataSparqlClient wikidata;
    private final WikidataApiClient api;
    private final Map<Datasource, WikidataSparqlClient> owned =
            new EnumMap<>(Datasource.class);

    /**
     * @param wikidata  the WDQS (default {@link Datasource#WIKIDATA}) client, owned by
     *                  the caller — not closed here
     * @param api       the Wikidata API client (API-mode identity search, etc.)
     * @param userAgent contact UA for the datasource-dependent clients this factory owns
     */
    public QueryFactory(
            WikidataSparqlClient wikidata, WikidataApiClient api, String userAgent) {

        this.wikidata = wikidata;
        this.api = api;
        // The datasource-dependent endpoints the factory owns. One line per datasource.
        owned.put(Datasource.DBPEDIA, new WikidataSparqlClient(
                userAgent, 2, WikidataSparqlClient.DBPEDIA_ENDPOINT));
    }

    /** A context with every datasource bound: WIKIDATA (default) plus the owned ones. */
    public QueryContext newContext() {
        QueryContext context = new QueryContext(wikidata, api);
        for (Map.Entry<Datasource, WikidataSparqlClient> e : owned.entrySet()) {
            context = context.withDatasource(e.getKey(), e.getValue());
        }
        return context;
    }

    @Override
    public void close() {
        owned.values().forEach(WikidataSparqlClient::close);
    }
}
