package wikidata.explore.query.core;

import org.junit.jupiter.api.Test;
import work.RequestLinks;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a logged Wikidata or DBpedia request browses to.
 *
 * <p>These rules used to live in {@code work.LogNode}, matching substrings of a step's
 * {@code queryType} — so a package meant to know no endpoints carried two of them, and only
 * those two sources could ever produce a link. They belong to the source, and are tested
 * against the source rather than through a log node.
 */
class WikidataRequestLinksTest {

    private static final String WBGET =
            "https://www.wikidata.org/w/api.php?action=wbgetentities"
                    + "&ids=Q1|Q2&props=labels|claims&languages=en|mul&format=json";

    private final RequestLinks links = new WikidataRequestLinks();

    /**
     * Sub-queries are logged under a default "SPARQL" type, but an action-API request is an
     * HTTP URL — sending the reader to a query service that cannot open it is worse than no
     * link, so the source corrects the label it was given.
     */
    @Test void anActionApiRequestIsRelabelledAndOpensItself() {
        RequestLinks.Resolved resolved = links.forRequest("SPARQL", WBGET);

        assertEquals("API", resolved.correctedQueryType());
        assertTrue(resolved.link().startsWith("Open request|"), resolved.link());
    }

    /** {@code ids=Q1|Q2} is rejected by java.net.URI, so the browser never opens it. */
    @Test void theRawPipesAnAipRequestCarriesAreEncoded() {
        String url = url(links.forRequest("SPARQL", WBGET));

        assertFalse(url.contains("|"), url);
        assertTrue(url.contains("%7C"), url);
        URI.create(url);
    }

    @Test void aSparqlQueryOpensInItsOwnDatasourcesQueryService() {
        String sparql = "SELECT ?e WHERE { ?e wdt:P31 wd:Q5 } LIMIT 1";

        assertEquals(Datasource.WIKIDATA.browseUrl(sparql),
                url(links.forRequest("SPARQL", sparql)));
        assertEquals(Datasource.DBPEDIA.browseUrl(sparql),
                url(links.forRequest("DBpedia SPARQL", sparql)));
    }

    @Test void aSparqlStepKeepsTheLabelItWasGiven() {
        assertNull(links.forRequest("SPARQL", "SELECT ?e WHERE { ?e wdt:P31 wd:Q5 }")
                .correctedQueryType());
    }

    @Test void aRequestThisSourceCannotBrowseHasNoLink() {
        assertNull(links.forRequest("Enrichment", "fill 12 fields"));
        assertNull(links.forRequest("SPARQL", "   "));
        assertNull(links.forRequest(null, WBGET));
        assertNull(links.forRequest("API", "no url anywhere in here"));
    }

    /**
     * Every datasource can say where a person opens one of its queries, and each says
     * somewhere different. Whether that is a separate service (Wikidata answers machines and
     * people at different hosts) or the same URL wearing its HTML form (DBpedia) is the
     * datasource's business — the point is that the log no longer has an opinion.
     */
    @Test void everyDatasourceKnowsWhereAPersonOpensItsQueries() {
        java.util.Set<String> browsed = new java.util.LinkedHashSet<>();
        for (Datasource datasource : Datasource.values()) {
            String browse = datasource.browseUrl("SELECT ?x WHERE { ?x ?y ?z }");
            assertFalse(datasource.browseLabel().isBlank(), datasource.toString());
            assertTrue(browse.contains("SELECT"), datasource + ": " + browse);
            URI.create(browse);
            browsed.add(browse);
        }
        assertEquals(Datasource.values().length, browsed.size(),
                "two datasources sending a reader to the same page would be a copy-paste");
    }

    private static String url(RequestLinks.Resolved resolved) {
        return resolved.link().substring(resolved.link().indexOf('|') + 1);
    }
}
