package wikipedia;

import com.fasterxml.jackson.databind.ObjectMapper;
import datasource.evidence.WikipediaArticle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikipediaArticleClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void retainsHeadingHierarchyBlocksLinksAndCategories() throws Exception {
        String extract = "<p>Achilles was a hero.</p>"
                + "<h2>Mythology</h2><p>He fought at <a title='Troy'>Troy</a>.</p>"
                + "<h3>Early life</h3><ul><li>His mother was Thetis.</li></ul>";
        String response = "{\"query\":{\"pages\":[{\"title\":\"Achilles\","
                + "\"lastrevid\":17,\"extract\":" + JSON.writeValueAsString(extract)
                + ",\"categories\":[{\"title\":\"Category:Greek heroes\"}]}]}}";

        WikipediaArticleClient.Article parsed = WikipediaArticleClient.parse(
                JSON.readTree(response));

        assertEquals("Achilles", parsed.structuredArticle().getDisplayName());
        assertEquals(java.util.List.of("Greek heroes"),
                parsed.structuredArticle().categories());
        assertEquals(java.util.List.of("Introduction", "Mythology"),
                parsed.structuredArticle().sections().stream()
                        .map(WikipediaArticle.Section::heading).toList());
        WikipediaArticle.Section mythology =
                parsed.structuredArticle().sections().get(1);
        assertEquals(java.util.List.of("Early life"), mythology.sections().stream()
                .map(WikipediaArticle.Section::heading).toList());
        assertEquals(WikipediaArticle.Block.Kind.PARAGRAPH,
                mythology.blocks().getFirst().kind());
        assertEquals("Troy", mythology.blocks().getFirst().links()
                .getFirst().targetTitle());
        assertEquals(WikipediaArticle.Block.Kind.LIST_ITEM,
                mythology.sections().getFirst().blocks().getFirst().kind());
        assertTrue(parsed.structuredArticle().introduction().text()
                .contains("Achilles was a hero."));
    }
}
