package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.rule.RuleNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An edge whose class includes subclasses lists them once, as a step of its own, and
 * reuses the list for every parent (#399). If they cannot be listed, the edge says so
 * and falls back to the subclass path.
 */
class EdgeSubclassTypesTest {

    private static final class CountingClient extends WikidataSparqlClient {
        int queries;
        boolean fail;
        CountingClient() { super("test", 1, "https://example.test/sparql"); }
        @Override public List<WikidataBinding> query(String sparql) {
            queries++;
            if (fail) throw new IllegalStateException("timeout");
            return List.of(new WikidataBinding(Map.of("t", "http://www.wikidata.org/entity/Q523")),
                    new WikidataBinding(Map.of("t", "http://www.wikidata.org/entity/Q5871")));
        }
    }

    private static RuleNode starEdge() {
        RuleNode node = new RuleNode("Star", "stars");
        node.membershipPid("P31");
        node.membershipQid("Q523");
        node.membershipFilterIncludesDescendants(true);
        return node;
    }

    private static final class RecordingLog implements GenerationLog {
        final List<String> lines = new ArrayList<>();
        @Override public void message(String text) { lines.add("MESSAGE " + text.strip()); }
        @Override public void subquery(String title, String request, String summary) {
            lines.add(title + " -> " + summary);
        }
    }

    @Test void theSubclassesAreListedOnceAsTheirOwnStep() throws Exception {
        CountingClient client = new CountingClient();
        RuleTreeExtractor extractor = new RuleTreeExtractor(client);
        RecordingLog log = new RecordingLog();

        List<String> first = extractor.membershipTypes(starEdge(), log);
        List<String> second = extractor.membershipTypes(starEdge(), log);

        assertEquals(List.of("Q523", "Q5871"), first);
        assertEquals(first, second);
        assertEquals(1, client.queries, "listed once, reused for every parent");
        assertEquals(List.of("Subclasses of Q523 -> 2 type(s)"), log.lines);
    }

    @Test void anEdgeWhoseSubclassesCannotBeListedSaysItFallsBack() throws Exception {
        CountingClient client = new CountingClient();
        client.fail = true;
        RecordingLog log = new RecordingLog();

        List<String> types = new RuleTreeExtractor(client).membershipTypes(starEdge(), log);

        assertTrue(types.isEmpty());
        assertTrue(log.lines.stream().anyMatch(l -> l.startsWith("Subclasses of Q523 -> FAILED")),
                log.lines.toString());
        assertTrue(log.lines.stream().anyMatch(l -> l.contains("uses the subclass path")),
                log.lines.toString());
    }

    @Test void anExactEdgeListsNothing() throws Exception {
        CountingClient client = new CountingClient();
        RuleNode exact = starEdge();
        exact.membershipFilterIncludesDescendants(false);

        assertTrue(new RuleTreeExtractor(client).membershipTypes(exact, new RecordingLog())
                .isEmpty());
        assertEquals(0, client.queries);
    }
}
