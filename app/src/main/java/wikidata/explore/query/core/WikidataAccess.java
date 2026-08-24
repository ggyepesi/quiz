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

    /**
     * Routes every bound endpoint's own request log — {@code START}, and {@code OK} /
     * {@code CANCELLED} / {@code ERROR} carrying {@code timeMs} — into {@code sink}.
     *
     * <p>Each client has always written these; the sink defaulted to a discarded
     * consumer and nothing ever replaced it, so a run that spent 385 s in one phase
     * could not say which query spent it (#116). The data was never missing, only
     * unaddressed.
     *
     * <p>Unlike the link rules, this cannot be installed when the source is bound: a
     * sink is per-RUN, and the context carrying a recorder is made later and elsewhere.
     * So a run says where its requests should report, once, for every endpoint it can
     * reach — rather than each acquisition remembering to wire the client it happens to
     * hold, which is how DBpedia came to be the only endpoint ever wired at all.
     */
    public static RequestLogs logRequests(
            QueryContext context, java.util.function.Consumer<String> sink) {
        java.util.function.Consumer<String> target = sink == null ? ignored -> {} : sink;
        java.util.List<AutoCloseable> scopes = new java.util.ArrayList<>();
        of(context).sparqlClients.forEach((datasource, client) -> scopes.add(
                client.requestLog(message ->
                        target.accept(labelled(datasource, message)))));
        return new RequestLogs(scopes);
    }

    /**
     * Puts the endpoint's name on the line that carries the event.
     *
     * <p>A client's messages open and close with blank lines — START prints the query
     * beneath itself — so prefixing the BLOCK put "[WIKIDATA]" alone above
     * "[SPARQL 4] START" and left an orphan below every message. The label went
     * missing from exactly the line it exists to identify, which is only noticed once
     * two endpoints interleave, which is when it matters.
     */
    private static String labelled(Datasource datasource, String message) {
        String body = message == null ? "" : message.strip();
        if (body.isEmpty()) return "";
        // No trailing newline: the log splits a message into lines itself, and one
        // added here became a blank line under every event.
        return "[" + datasource.name() + "] " + body;
    }

    /** The per-run endpoint log registrations; closing restores the worker thread's
     * previous registrations and releases references to the finished run. */
    public static final class RequestLogs implements AutoCloseable {
        private final java.util.List<AutoCloseable> scopes;
        private RequestLogs(java.util.List<AutoCloseable> scopes) {
            this.scopes = java.util.List.copyOf(scopes);
        }
        @Override public void close() {
            for (int i = scopes.size() - 1; i >= 0; i--) {
                try { scopes.get(i).close(); }
                catch (Exception ignored) { }
            }
        }
    }

    public static WikidataApiClient api(QueryContext context) {
        return of(context).api();
    }
}
