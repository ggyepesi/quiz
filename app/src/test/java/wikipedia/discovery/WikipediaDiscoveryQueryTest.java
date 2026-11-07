package wikipedia.discovery;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import work.QueryContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * #461: discovery read each article with one parse request and its infobox with a second,
 * so 50 articles cost 100 requests, and an edit between the two paired an article with a
 * different revision's infobox while the result tabs presented them as one article. Both
 * now come from one response.
 */
class WikipediaDiscoveryQueryTest {

    private static final String MAAT = """
            {"parse":{"title":"Maat","revid":42,
              "wikitext":"{{Infobox deity\\n| name = Maat\\n| symbol = Ostrich feather\\n}}\\nMaat represented truth.",
              "text":"<table class='infobox'><tr><th class='infobox-label'>Symbol</th><td class='infobox-data'>Ostrich feather</td></tr></table><p>Maat represented truth.</p>",
              "categories":[{"category":"Egyptian_goddesses"}]}}
            """;

    @Test void oneRequestPerArticleCarriesTheArticleAndItsInfoboxFromOneRevision()
            throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var client = new wikipedia.WikipediaArticleClient(uri -> {
            requests.incrementAndGet();
            return MAAT;
        });
        WikidataDynamicObject maat = new WikidataDynamicObject("Q170471", "Maat");
        maat.enwikiTitle("Maat");

        List<WikipediaDiscoveryQuery.Article> result =
                new WikipediaDiscoveryQuery(List.of(maat), client).execute(new QueryContext());

        assertEquals(1, requests.get(), "one parse request per selected article");
        var article = result.getFirst();
        assertNotNull(article.infobox());
        assertEquals("Ostrich feather", article.infobox().parameters().get("symbol"));
        assertEquals("Ostrich feather", article.infobox().renderedFields().get("Symbol"));
        assertEquals(article.article().document().revision(),
                article.infobox().document().revision(),
                "the article and its infobox describe the same revision");
    }

    @Test void aPageWithoutAnInfoboxIsAvailableWithNoInfobox() throws Exception {
        var client = new wikipedia.WikipediaArticleClient(uri -> """
                {"parse":{"title":"Maat","revid":42,"wikitext":"Maat represented truth.",
                  "text":"<p>Maat represented truth.</p>","categories":[]}}
                """);
        WikidataDynamicObject maat = new WikidataDynamicObject("Q170471", "Maat");
        maat.enwikiTitle("Maat");

        var article = new WikipediaDiscoveryQuery(List.of(maat), client)
                .execute(new QueryContext()).getFirst();

        assertEquals(true, article.available());
        assertNull(article.infobox());
        assertEquals("", article.problem());
    }
}
