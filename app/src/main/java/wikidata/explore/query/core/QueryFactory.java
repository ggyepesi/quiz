package wikidata.explore.query.core;

import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import work.QueryContext;

import java.util.EnumMap;
import java.util.Map;

/**
 * The single authority on which {@link Datasource}s exist and how each maps to a
 * live endpoint client. The factory stamps every datasource's client into the
 * {@link QueryContext}s it mints; each SPARQL operation then names the datasource
 * it needs. This also supports workflows that intentionally use more than one
 * datasource, such as sampling QIDs on Wikidata before querying DBpedia.
 *
 * <p>The primary WIKIDATA (WDQS) client is <em>adopted</em> from the caller (it is
 * often used for other things too, e.g. cancel wiring), so the factory does not
 * close it. The datasource-dependent clients the factory creates (DBpedia) it
 * owns and closes in {@link #close()}. Adding a new datasource is a one-line
 * addition to {@link Datasource}; no caller has to learn about it.
 */
public final class QueryFactory implements AutoCloseable {

    private final WikidataSparqlClient wikidata;
    private final WikidataApiClient api;
    private final Map<Datasource, WikidataSparqlClient> owned =
            new EnumMap<>(Datasource.class);
    private boolean closed;

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
        // WIKIDATA is adopted from the caller. Every other enum value is factory-owned,
        // making Datasource the single endpoint registry.
        for (Datasource datasource : Datasource.values()) {
            if (datasource != Datasource.WIKIDATA) {
                owned.put(datasource,
                        new WikidataSparqlClient(userAgent, 2, datasource.endpoint()));
            }
        }
    }

    /** A context with every datasource bound: WIKIDATA (default) plus the owned ones. */
    public synchronized QueryContext newContext() {
        if (closed) {
            throw new IllegalStateException("QueryFactory is closed");
        }
        WikidataAccess access = WikidataAccess.of(wikidata, api);
        for (Map.Entry<Datasource, WikidataSparqlClient> e : owned.entrySet()) {
            access = access.with(e.getKey(), e.getValue());
        }
        return access.bind();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        owned.values().forEach(WikidataSparqlClient::close);
    }
}
