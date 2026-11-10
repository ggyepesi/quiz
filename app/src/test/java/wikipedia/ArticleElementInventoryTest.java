package wikipedia;

import datasource.evidence.WikipediaArticle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The article structure reads a fixed set of elements; everything else in the page was
 * dropped without trace, so no comparison could show it was missing. The inventory lists
 * every block-level element kind under the name the page gives it, together with what
 * the structure does with it.
 */
class ArticleElementInventoryTest {

    private static final String HTML = """
            <div class="hatnote navigation-not-searchable">For other uses, see Maat (disambiguation).</div>
            <table class="infobox vcard"><tr><th>Symbol</th><td>Ostrich feather</td></tr></table>
            <p>Maat was the goddess of truth.</p>
            <dl><dd>Indented commentary on Maat.</dd></dl>
            <figure><figcaption>Maat wearing an ostrich feather.</figcaption></figure>
            <h2>Worship</h2>
            <p>Her cult was widespread.</p>
            <blockquote><p>I have done Maat.</p></blockquote>
            <ol class="references"><li>Wilkinson 2003, p. 150.</li></ol>
            """;

    private static Map<String, ArticleElementInventory.ElementKind> inventory() {
        return WikipediaArticleClient.parseArticleText(HTML).elements().stream()
                .collect(Collectors.toMap(kind -> kind.element() + " / " + kind.retainedAs(),
                        kind -> kind));
    }

    @Test void everyBlockLevelElementIsListedWithWhatTheStructureDoesWithIt() {
        Map<String, ArticleElementInventory.ElementKind> kinds = inventory();

        assertEquals(2, kinds.get("p / PARAGRAPH").occurrences());
        assertTrue(kinds.containsKey("table.infobox.vcard / TABLE"),
                "the infobox is read as an ordinary table block: " + kinds.keySet());
        assertTrue(kinds.containsKey("li / LIST_ITEM"), "a footnote becomes a list item");
        assertTrue(kinds.containsKey("p / Inside a retained block"));
        assertTrue(kinds.containsKey("h2 / Section heading"));
        for (String dropped : List.of("div.hatnote.navigation-not-searchable",
                "dl", "figure", "ol.references")) {
            assertTrue(kinds.containsKey(dropped + " / Not retained"),
                    dropped + " is not retained, and visible as such: " + kinds.keySet());
        }
        assertTrue(kinds.containsKey("figcaption / CAPTION"));
        assertEquals("Indented commentary on Maat.",
                kinds.get("dd / DEFINITION").example());
    }

    /** The inventory reports what the structure did, not a prediction about it. */
    @Test void theRetainedKindsCountExactlyTheBlocksTheStructureBuilt() {
        var parsed = WikipediaArticleClient.parseArticleText(HTML);
        java.util.Set<String> blockKinds = java.util.Arrays.stream(
                        WikipediaArticle.Block.Kind.values())
                .map(Enum::name).collect(Collectors.toSet());

        int listedAsBlocks = parsed.elements().stream()
                .filter(kind -> blockKinds.contains(kind.retainedAs()))
                .mapToInt(ArticleElementInventory.ElementKind::occurrences).sum();
        int built = countBlocks(parsed.structuredSections());

        assertEquals(built, listedAsBlocks);
    }

    private static int countBlocks(List<WikipediaArticle.Section> sections) {
        int count = 0;
        for (WikipediaArticle.Section section : sections) {
            count += section.blocks().size() + countBlocks(section.sections());
        }
        return count;
    }
}
