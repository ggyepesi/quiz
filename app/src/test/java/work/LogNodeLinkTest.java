package work;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The log shows a link; it does not decide what a request browses to.
 *
 * <p>It used to decide, by matching substrings of a step's {@code queryType} to build
 * {@code query.wikidata.org} and {@code dbpedia.org} URLs — endpoint knowledge in the one
 * package that is meant to have none, and no way for a third source to be browsable at all.
 * What is left here is rendering: ask whoever the application installed, show what comes
 * back, and take a corrected label when the source says the step was mislabelled.
 */
class LogNodeLinkTest {

    @AfterEach void leaveNothingInstalled() {
        LogNode.linksProvidedBy(null);
    }

    @Test void aNodeShowsWhateverTheInstalledSourceHandsIt() {
        LogNode.linksProvidedBy((queryType, request) ->
                RequestLinks.Resolved.of("Open somewhere", "https://example.test/" + request));

        LogNode node = new LogNode(LogKind.QUERY, "step");
        node.queryType("Anything");
        node.appendRequest("abc");

        assertEquals("Open somewhere|https://example.test/abc", node.link());
    }

    @Test void aSourceMayCorrectTheLabelTheStepWasLoggedUnder() {
        LogNode.linksProvidedBy((queryType, request) ->
                RequestLinks.Resolved.of("Open request", "https://example.test").labelledAs("API"));

        LogNode node = new LogNode(LogKind.QUERY, "wbgetentities 1/3");
        node.queryType("SPARQL");
        node.appendRequest("https://example.test");

        assertTrue(node.getDisplayName().contains("API"), node.getDisplayName());
        assertTrue(!node.getDisplayName().contains("SPARQL"), node.getDisplayName());
    }

    /** With nothing installed the log still works — it just cannot offer to open anything. */
    @Test void withNoSourceInstalledNothingIsBrowsable() {
        LogNode.linksProvidedBy(null);

        LogNode node = new LogNode(LogKind.QUERY, "members");
        node.queryType("SPARQL");
        node.appendRequest("SELECT ?e WHERE { ?e wdt:P31 wd:Q5 }");

        assertNull(node.link());
        assertTrue(node.getDisplayName().contains("SPARQL"), "and the label it was given stands");
    }

    @Test void aRequestTheSourceDeclinesHasNoLink() {
        LogNode.linksProvidedBy((queryType, request) -> null);

        LogNode node = new LogNode(LogKind.QUERY, "step");
        node.queryType("Enrichment");
        node.appendRequest("fill 12 fields");

        assertNull(node.link());
    }
}
