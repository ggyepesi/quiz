package wikidata.explore.query.core;

import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.query.log.LogStep;
import wikidata.explore.query.log.LogNode;
import wikidata.explore.query.log.LogStepBody;
import wikidata.explore.query.log.WorkflowRecorder;

import java.util.EnumMap;
import java.util.Map;

public class QueryContext {
    // A SPARQL client per datasource, so a query routes to the endpoint it was generated
    // for. The single-client constructor makes WIKIDATA the default; bind others (DBpedia)
    // with withDatasource — the binding is explicit, not a runner-configuration accident.
    private final Map<Datasource, WikidataSparqlClient> sparqlClients;
    private final WikidataApiClient apiClient;
    private final WorkflowRecorder recorder;
    private final LogNode processParent;
    private final process.CancellationToken cancellation;

    /** {@code sparqlClient} is the WIKIDATA (default) client. Add DBpedia (or another
     *  datasource) via {@link #withDatasource}. */
    public QueryContext(
            WikidataSparqlClient sparqlClient,
            WikidataApiClient apiClient) {

        this(clients(Datasource.WIKIDATA, sparqlClient), apiClient, null, null,
                new process.CancellationToken());
    }

    private QueryContext(
            Map<Datasource, WikidataSparqlClient> sparqlClients,
            WikidataApiClient apiClient,
            WorkflowRecorder recorder,
            LogNode processParent,
            process.CancellationToken cancellation) {

        this.sparqlClients = sparqlClients;
        this.apiClient = apiClient;
        this.recorder = recorder;
        this.processParent = processParent;
        this.cancellation = cancellation == null
                ? new process.CancellationToken() : cancellation;
    }

    private static Map<Datasource, WikidataSparqlClient> clients(
            Datasource datasource, WikidataSparqlClient client) {
        Map<Datasource, WikidataSparqlClient> map = new EnumMap<>(Datasource.class);
        if (client != null) {
            map.put(datasource, client);
        }
        return map;
    }

    /** A context that also routes {@code datasource} queries to {@code client}: the
     *  explicit datasource↔endpoint binding, so a query for it can't hit the wrong one. */
    public QueryContext withDatasource(Datasource datasource, WikidataSparqlClient client) {
        Map<Datasource, WikidataSparqlClient> next = new EnumMap<>(sparqlClients);
        if (client != null) {
            next.put(datasource, client);
        }
        return new QueryContext(next, apiClient, recorder, processParent, cancellation);
    }

    public QueryContext withRecorder(WorkflowRecorder recorder) {
        return new QueryContext(sparqlClients, apiClient, recorder, null, cancellation);
    }

    /** Binds adapted-query steps beneath their owning Process subprocess. */
    public QueryContext withRecorder(WorkflowRecorder recorder, LogNode processParent) {
        return new QueryContext(sparqlClients, apiClient, recorder, processParent, cancellation);
    }

    public QueryContext withCancellation(process.CancellationToken token) {
        return new QueryContext(sparqlClients, apiClient, recorder, processParent, token);
    }

    public process.CancellationToken cancellation() { return cancellation; }

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

    /** Cancels every distinct active SPARQL transport owned by this context. */
    public void cancelActiveQueries() {
        sparqlClients.values().stream()
                .distinct()
                .forEach(WikidataSparqlClient::cancelCurrentQuery);
    }

    /**
     * Runs {@code body} inside a log step nested under the current
     * workflow. The step is opened before the body runs and completed
     * after it returns (ok) or throws (failed / cancelled), so the body
     * can never leave the log tree unbalanced.
     */
    public <T> T step(
            String title,
            String queryType,
            String skeleton,
            Map<String, String> parameters,
            LogStepBody<T> body) throws Exception {

        if (recorder == null) {
            return body.run(LogStep.disabled());
        }

        return recorder.stepUnder(
                processParent, title, queryType, skeleton, parameters, body);
    }

    public void message(String text) {
        if (recorder != null) {
            recorder.messageUnder(processParent, text);
        }
    }
}
