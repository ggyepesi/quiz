package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.log.LogNode;
import wikidata.explore.query.workflow.QueryWorkflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DBpediaLookupTest {

    @Test
    void valueLookupUsesTheSharedQueryWorkflowAndStructuredRequestLog() throws Exception {
        try (FakeClient client = new FakeClient(List.of(
                new WikidataBinding(Map.of(
                        "val", "http://dbpedia.org/resource/Budapest",
                        "valLabel", "Budapest")),
                new WikidataBinding(Map.of(
                        "val", "http://dbpedia.org/resource/Budapest"))))) {

            List<LogNode> roots = new ArrayList<>();
            List<String> accepted = new ArrayList<>();
            QueryWorkflow<List<String>> workflow = new QueryWorkflow<>(
                    new QueryContext(client, null),
                    accepted::addAll,
                    (root, added) -> {
                        if (added && !roots.contains(root)) {
                            roots.add(root);
                        }
                    });

            List<String> result = workflow.run(DBpediaLookup.values("Q1", "capital"));

            assertEquals(List.of("Budapest"), result);
            assertEquals(result, accepted);
            assertEquals(1, roots.size());
            LogNode step = roots.get(0).steps().iterator().next();
            assertTrue(step.request().contains("dbo:capital"));
            assertEquals("1 candidate(s)", step.summary());
        }
    }

    @Test
    void imageLookupSupportsLabelBasedSubjects() throws Exception {
        try (FakeClient client = new FakeClient(List.of(
                new WikidataBinding(Map.of("img", "https://example.test/image.jpg"))))) {

            List<String> result = DBpediaLookup.images(null, "Ada Lovelace")
                    .execute(new QueryContext(client, null));

            assertEquals(List.of("https://example.test/image.jpg"), result);
            assertTrue(client.lastQuery.contains("\"Ada Lovelace\"@en"));
        }
    }

    private static final class FakeClient extends WikidataSparqlClient {
        private final List<WikidataBinding> rows;
        private String lastQuery;

        private FakeClient(List<WikidataBinding> rows) {
            super("test", 1, "https://example.test/sparql");
            this.rows = rows;
        }

        @Override public List<WikidataBinding> query(String sparql) {
            lastQuery = sparql;
            return rows;
        }
    }
}
