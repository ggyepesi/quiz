package wikidata.explore.query.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The log link derived from a node's request + queryType. An action-API request
 * (wbgetentities) is logged with the default "SPARQL" type and carries raw '|'
 * characters (ids=Q1|Q2, props=labels|claims); the label must read API and the
 * link must be openable (percent-encoded), not a dead WDQS page.
 */
class LogNodeLinkTest {

    private static final String WBGET =
            "https://www.wikidata.org/w/api.php?action=wbgetentities"
                    + "&ids=Q1|Q2&props=labels|claims&languages=en|mul&format=json";

    @Test void apiRequestIsRelabelledAndItsLinkEncoded() {
        LogNode node = new LogNode(LogKind.QUERY, "wbgetentities 1/3");
        node.queryType("SPARQL");        // the default subquery type
        node.appendRequest(WBGET);

        // Relabelled away from the misleading "SPARQL".
        assertTrue(node.getDisplayName().contains("API"), node.getDisplayName());
        assertFalse(node.getDisplayName().contains("SPARQL"), node.getDisplayName());

        // The link opens the request (not a query-service page) and is encoded so
        // java.net.URI accepts it — no raw '|'.
        String link = node.link();
        assertTrue(link.startsWith("Open request|"), link);
        String url = link.substring(link.indexOf('|') + 1);
        assertFalse(url.contains("|"), "raw pipes must be encoded: " + url);
        assertTrue(url.contains("%7C"), url);
        // Sanity: the encoded URL is a legal URI.
        java.net.URI.create(url);
    }

    @Test void realSparqlKeepsQueryServiceLink() {
        LogNode node = new LogNode(LogKind.QUERY, "members");
        node.queryType("SPARQL");
        node.appendRequest("SELECT ?e WHERE { ?e wdt:P31 wd:Q5 } LIMIT 1");

        assertTrue(node.getDisplayName().contains("SPARQL"), node.getDisplayName());
        assertTrue(node.link().startsWith("Open in query service|"), node.link());
    }
}
