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
                new work.QueryContext());

        assertNotNull(result);
        assertEquals("7", result.document().revision());
        assertTrue(requested.get().getRawQuery()
                .contains("prop=wikitext%7Ctext%7Crevid"));
    }

    @Test void renderedRowsExposeValuesCalculatedByWrapperInfoboxes() throws Exception {
        String html = """
                <table class="infobox">
                  <tbody>
                    <tr><th class="infobox-label">Group</th>
                        <td class="infobox-data">group 8</td></tr>
                    <tr><th class="infobox-label">Block</th>
                        <td class="infobox-data"><a>d-block</a></td></tr>
                    <tr><th class="infobox-label">Electrons per shell</th>
                        <td class="infobox-data">2, 8, 14, 2</td></tr>
                    <tr><td><table><tr><th class="infobox-label">Nested presentation</th>
                        <td class="infobox-data">must not become a field</td></tr></table></td></tr>
                  </tbody>
                </table>
                """;
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var result = WikipediaInfoboxClient.parseResponse(json.readTree(
                "{\"parse\":{\"title\":\"Iron\",\"revid\":9,\"wikitext\":"
                        + json.valueToTree("{{Infobox iron}}") + ",\"text\":"
                        + json.valueToTree(html) + "}}"));

        assertNotNull(result);
        assertEquals("group 8", result.valueOf(
                datasource.evidence.InfoboxParameters.Key.parse("Rendered.Group")));
        assertEquals("d-block", result.renderedFields().get("Block"));
        assertEquals("2, 8, 14, 2", result.renderedFields().get("Electrons per shell"));
        assertFalse(result.renderedFields().containsKey("Nested presentation"));
    }

    /** #469: text-only infobox rows lost exactly the source structure needed for
     * disambiguation and for image-only values such as Egyptian hieroglyphs. */
    @Test void renderedRowsRetainWikipediaTargetsAndImagesBehindTheirTextProjection()
            throws Exception {
        String html = """
                <table class="infobox">
                  <tr><th class="infobox-label">Symbol</th>
                    <td class="infobox-data"><a href="/wiki/Ostrich_feather"
                      title="Feather">ostrich feather</a>, Balance</td></tr>
                  <tr><th class="infobox-label">Parents</th>
                    <td class="infobox-data"><a href="/wiki/Ra">Ra</a> and
                      <a href="/wiki/Hathor">Hathor</a></td></tr>
                  <tr><th class="infobox-label">Name in hieroglyphs</th>
                    <td class="infobox-data"><img alt="Maat in hieroglyphs"
                      src="//upload.wikimedia.org/maat.svg"></td></tr>
                </table>
                """;
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var result = WikipediaInfoboxClient.parseResponse(json.readTree(
                "{\"parse\":{\"title\":\"Maat\",\"revid\":42,\"wikitext\":"
                        + json.valueToTree("{{Infobox deity|symbol=Ostrich feather}}")
                        + ",\"text\":" + json.valueToTree(html) + "}}"));

        var symbol = result.renderedValues().get("Symbol");
        assertEquals("ostrich feather, Balance", symbol.text());
        assertEquals("Feather", symbol.links().getFirst().targetTitle());
        assertEquals("Ra", result.renderedValues().get("Parents")
                .links().getFirst().targetTitle());
        var glyph = result.renderedValues().get("Name in hieroglyphs");
        assertEquals("", glyph.text(), "an image-only value must not invent text");
        assertEquals("https://upload.wikimedia.org/maat.svg",
                glyph.media().getFirst().mediaUrl());
        assertEquals("ostrich feather, Balance", result.renderedFields().get("Symbol"),
                "existing Rendered.Symbol configurations keep their scalar projection");
    }
}
