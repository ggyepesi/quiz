package wikipedia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WikipediaInfoboxClientTest {
    @Test void parsesNamedParametersWithoutSplittingNestedMarkup() {
        var box = WikipediaInfoboxClient.parseWikitext("""
                lead
                {{Infobox film
                | name = Blood Diamond
                | starring = [[Leonardo DiCaprio|DiCaprio]]
                | released = {{Film date|2006|12|08}}
                | country = [[United States]]<br>[[Germany]]
                }}
                body
                """);
        assertNotNull(box);
        assertEquals("Infobox film", box.template());
        assertEquals("DiCaprio", box.parameters().get("starring"));
        assertEquals("{{Film date|2006|12|08}}", box.parameters().get("released"));
        assertEquals("United States Germany", box.parameters().get("country"));
    }

    @Test void ignoresOrdinaryTemplatesBeforeTheInfobox() {
        var box = WikipediaInfoboxClient.parseWikitext(
                "{{Short description|film}}\n{{Infobox film|name=True Grit|year=2010}}");
        assertNotNull(box);
        assertEquals("2010", box.parameters().get("year"));
    }

    @Test void theRequestAsksForTheRevisionTheResultRequires() throws Exception {
        var requested = new java.util.concurrent.atomic.AtomicReference<java.net.URI>();
        WikipediaInfoboxClient client = new WikipediaInfoboxClient(uri -> {
            requested.set(uri);
            return """
                    {"parse":{"title":"Blood Diamond","revid":7,
                    "wikitext":"{{Infobox film|country=United States}}"}}
                    """;
        });

        var result = client.byTitle("Blood Diamond").execute(
                new wikidata.explore.query.core.QueryContext(null, null));

        assertNotNull(result);
        assertEquals("7", result.document().revision());
        assertTrue(requested.get().getRawQuery().contains("prop=wikitext%7Crevid"));
    }
}
