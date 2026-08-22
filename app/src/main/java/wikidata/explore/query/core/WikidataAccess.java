package wikidata.explore.query.core;

import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import work.CancellableWork;
import work.QueryContext;

import java.util.EnumMap;
import java.util.Map;

/**
 * Wikidata endpoint access, carried by a {@link QueryContext} as a capability.
 *
 * <p>These clients used to be fields on the context itself, so a query that never touched
 * Wikidata still received them and a package that never queried anything still imported
 * them. Here they are one capability among however many a runner chooses to bind, and a
 * query that needs the endpoints says so at the point of use.
 */
public final class WikidataAccess implements CancellableWork {

    private final Map<Datasource, WikidataSparqlClient> sparqlClients;
    private final WikidataApiClient apiClient;

    private WikidataAccess(
            Map<Datasource, WikidataSparqlClient> sparqlClients, WikidataApiClient apiClient) {
        this.sparqlClients = sparqlClients;
        this.apiClient = apiClient;
    }

    /** {@code sparql} is the WIKIDATA (default) client; bind others with {@link #with}. */
    public static WikidataAccess of(WikidataSparqlClient sparql, WikidataApiClient api) {
        // Binding this source is exactly when its requests become browsable, so the log's
        // link rules arrive with it. Installing at each entry point instead was one more
        // thing to remember, and forgetting it costs a link with nothing to say why.
        WikidataRequestLinks.install();
        Map<Datasource, WikidataSparqlClient> clients = new EnumMap<>(Datasource.class);
        if (sparql != null) {
            clients.put(Datasource.WIKIDATA, sparql);
        }
        return new WikidataAccess(clients, api);
    }

    /** Routes {@code datasource} queries to {@code client}: the explicit datasource↔endpoint
     *  binding, so a query for it can't hit the wrong one. */
    public WikidataAccess with(Datasource datasource, WikidataSparqlClient client) {
        Map<Datasource, WikidataSparqlClient> next = new EnumMap<>(sparqlClients);
        if (client != null) {
            next.put(datasource, client);
        }
        return new WikidataAccess(next, apiClient);
    }

    /** A fresh context offering this access and nothing else. */
    public QueryContext bind() {
        return bindTo(new QueryContext());
    }

    public QueryContext bindTo(QueryContext base) {
        return (base == null ? new QueryContext() : base).with(WikidataAccess.class, this);
    }

    /** The SPARQL client explicitly bound to {@code datasource}. Missing bindings fail here,
     *  before a query can accidentally fall through to some default endpoint. */
    public WikidataSparqlClient sparql(Datasource datasource) {
        WikidataSparqlClient client = sparqlClients.get(datasource);
        if (client == null) {
            throw new IllegalStateException("No SPARQL client configured for " + datasource);
        }
        return client;
    }

    public WikidataApiClient api() {
        return apiClient;
    }

    /** Cancels every distinct active SPARQL transport this access owns. */
    @Override public void cancelActiveWork() {
        sparqlClients.values().stream()
                .distinct()
                .forEach(WikidataSparqlClient::cancelCurrentQuery);
    }

    // --- what a query at the point of use asks for -----------------------------------

    public static WikidataAccess of(QueryContext context) {
        return context.require(WikidataAccess.class);
    }

    public static WikidataSparqlClient sparql(QueryContext context, Datasource datasource) {
        return of(context).sparql(datasource);
    }

    public static WikidataApiClient api(QueryContext context) {
        return of(context).api();
    }
}
