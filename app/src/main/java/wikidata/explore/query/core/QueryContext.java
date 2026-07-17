package wikidata.explore.query.core;

import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.query.log.LogStep;
import wikidata.explore.query.log.LogStepBody;
import wikidata.explore.query.log.WorkflowRecorder;

import java.util.Map;

public class QueryContext {
    private final WikidataSparqlClient sparqlClient;
    private final WikidataApiClient apiClient;
    private final WorkflowRecorder recorder;

    public QueryContext(
            WikidataSparqlClient sparqlClient,
            WikidataApiClient apiClient) {

        this(sparqlClient, apiClient, null);
    }

    private QueryContext(
            WikidataSparqlClient sparqlClient,
            WikidataApiClient apiClient,
            WorkflowRecorder recorder) {

        this.sparqlClient = sparqlClient;
        this.apiClient = apiClient;
        this.recorder = recorder;
    }

    public QueryContext withRecorder(WorkflowRecorder recorder) {
        return new QueryContext(
                sparqlClient,
                apiClient,
                recorder);
    }

    public WikidataSparqlClient sparql() {
        return sparqlClient;
    }

    public WikidataApiClient api() {
        return apiClient;
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

        return recorder.step(title, queryType, skeleton, parameters, body);
    }

    public void message(String text) {
        if (recorder != null) {
            recorder.message(text);
        }
    }
}
