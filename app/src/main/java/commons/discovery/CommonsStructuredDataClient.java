package commons.discovery;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import work.CancellableWork;

import java.util.List;

/**
 * Read-only access to the programmatic Wikimedia Commons structured-data endpoint.
 *
 * <p>The endpoint is QLever's Wikimedia Commons index, recommended by the Commons
 * query-service documentation for programmatic access.  Keeping it behind this small
 * source client prevents a Commons endpoint from masquerading as the application's
 * Wikidata datasource merely because both happen to speak SPARQL.</p>
 */
public final class CommonsStructuredDataClient implements AutoCloseable, CancellableWork {
    public static final String ENDPOINT =
            "https://qlever.dev/api/wikimedia-commons";
    private static final String USER_AGENT =
            "Quiz Commons Discovery/1.0 (https://github.com/ggyepesi/quiz)";

    private final WikidataSparqlClient sparql;

    public CommonsStructuredDataClient() {
        this(new WikidataSparqlClient(USER_AGENT, 2, ENDPOINT));
    }

    CommonsStructuredDataClient(WikidataSparqlClient sparql) {
        this.sparql = java.util.Objects.requireNonNull(sparql);
    }

    public List<WikidataBinding> query(String query) throws Exception {
        return sparql.query(query);
    }

    @Override public void cancelActiveWork() {
        sparql.cancelCurrentQuery();
    }

    @Override public void close() {
        sparql.close();
    }
}
