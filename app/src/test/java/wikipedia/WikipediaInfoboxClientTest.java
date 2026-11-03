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
}
