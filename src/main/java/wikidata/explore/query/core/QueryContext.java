package wikidata.explore.query.core;

import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;

public class QueryContext {
    private final WikidataSparqlClient sparqlClient;
    private final WikidataApiClient apiClient;
    private final QueryEventSink eventSink;

    public QueryContext(
            WikidataSparqlClient sparqlClient,
            WikidataApiClient apiClient,
            QueryEventSink eventSink) {

        this.sparqlClient = sparqlClient;
        this.apiClient = apiClient;
        this.eventSink = eventSink;
    }

    public WikidataSparqlClient sparql() {
        return sparqlClient;
    }

    public WikidataApiClient api() {
        return apiClient;
    }

    public void logText(String text) {
        if (eventSink instanceof TextQueryEventSink t) {
            t.text(text);
        }
    }
}