package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.FakeWikidataSparqlClient;
import wikidata.WikidataBinding;
import wikidata.explore.model.EntityBound;
import work.CancellationToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationalObjectPopulationLoaderTest {

    @Test void oneAllResultsQueryIsReplacedByBoundedOrderedPages() throws Exception {
        List<String> requests = new ArrayList<>();
        FakeWikidataSparqlClient client = new FakeWikidataSparqlClient() {
            @Override public List<WikidataBinding> query(String query) {
                requests.add(query);
                return List.of();
            }
        };

        RelationalObjectPopulationLoader.load(client,
                EntityBound.instancesOf("Q4164871"), null, new CancellationToken());

        assertEquals(2, requests.size(), "empty page plus one lightweight confirmation");
        assertTrue(requests.getFirst().contains("ORDER BY STR(?value)\nLIMIT 1000"));
        assertTrue(requests.getLast().contains("ORDER BY STR(?value)\nLIMIT 1"));
        assertTrue(requests.stream().allMatch(q -> q.contains(
                "STRSTARTS(STR(?value), \"http://www.wikidata.org/entity/Q\")")));
    }

    @Test void aShortValidPageContinuesFromItsLastReturnedEntity() throws Exception {
        List<String> requests = new ArrayList<>();
        FakeWikidataSparqlClient client = new FakeWikidataSparqlClient() {
            int call;
            @Override public List<WikidataBinding> query(String query) {
                requests.add(query);
                call++;
                if (call == 1) return rows("Q100", "Q101");
                if (call == 2) return rows("Q102");
                return List.of();
            }

            private List<WikidataBinding> rows(String... qids) {
                return java.util.Arrays.stream(qids).map(qid -> new WikidataBinding(
                        Map.of("value", "http://www.wikidata.org/entity/" + qid)))
                        .toList();
            }
        };

        List<String> values = RelationalObjectPopulationLoader.load(client,
                EntityBound.instancesOf("Q4164871"), null, new CancellationToken());

        assertEquals(List.of("Q100", "Q101", "Q102"), values);
        assertEquals(4, requests.size(),
                "two short data pages, then an empty page and its confirmation");
        assertTrue(requests.get(1).contains(
                "FILTER(STR(?value) > \"http://www.wikidata.org/entity/Q101\")"));
        assertTrue(requests.get(2).contains(
                "FILTER(STR(?value) > \"http://www.wikidata.org/entity/Q102\")"));
    }

    @Test void theProcessCancellationTokenStopsBeforeTheFirstPage() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        List<String> requests = new ArrayList<>();
        FakeWikidataSparqlClient client = new FakeWikidataSparqlClient() {
            @Override public List<WikidataBinding> query(String query) {
                requests.add(query);
                return List.of();
            }
        };

        assertThrows(CancellationException.class, () ->
                RelationalObjectPopulationLoader.load(client,
                        EntityBound.instancesOf("Q4164871"), null, token));
        assertTrue(requests.isEmpty());
    }

    @Test void aHeavyPageHalvesAndLaterPagesKeepTheWorkingSize() throws Exception {
        List<String> requests = new ArrayList<>();
        FakeWikidataSparqlClient client = new FakeWikidataSparqlClient() {
            @Override public List<WikidataBinding> query(String query) {
                requests.add(query);
                if (query.endsWith("LIMIT 1000")) {
                    throw new RuntimeException(new batch.ResponseTimeoutException(
                            "request timed out", null));
                }
                if (query.endsWith("LIMIT 500") && !query.contains("FILTER(STR(?value) >")) {
                    return List.of(new WikidataBinding(Map.of(
                            "value", "http://www.wikidata.org/entity/Q100")));
                }
                return List.of();
            }
        };

        List<String> values = RelationalObjectPopulationLoader.load(client,
                EntityBound.instancesOf("Q4164871"), null, new CancellationToken());

        assertEquals(List.of("Q100"), values);
        assertEquals(List.of(1000, 500, 500, 1), requests.stream()
                .map(RelationalObjectPopulationLoaderTest::limitOf).toList(),
                "the failed cursor is preserved and 500 remains the main page size");
    }

    @Test void anExhaustedPageFailureCannotBecomeAPartialPopulation() {
        FakeWikidataSparqlClient client = new FakeWikidataSparqlClient() {
            @Override public List<WikidataBinding> query(String query) {
                throw new wikidata.WikidataSparqlClient.TruncatedResponseException(
                        "partial", new IllegalStateException("truncated"));
            }
        };

        assertThrows(batch.BatchExecutor.BatchFailedException.class, () ->
                RelationalObjectPopulationLoader.load(client,
                        EntityBound.instancesOf("Q4164871"), null,
                        new CancellationToken()));
    }

    private static int limitOf(String query) {
        return Integer.parseInt(query.substring(query.lastIndexOf("LIMIT ") + 6));
    }
}
