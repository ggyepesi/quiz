package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikidataLinksTest {

    @Test void linksBareIdsInText() {
        String h = WikidataLinks.html("nominee Q103916 via P1411 today");
        assertTrue(h.contains("<a href=\"https://www.wikidata.org/wiki/Q103916\">Q103916</a>"), h);
        assertTrue(h.contains("<a href=\"https://www.wikidata.org/wiki/Property:P1411\">P1411</a>"), h);
        // surrounding plain text preserved
        assertTrue(h.startsWith("nominee "), h);
        assertTrue(h.endsWith(" today"), h);
    }

    @Test void linkHtmlShowsLabelLinksToId() {
        // The headline case: a label that travels with its qid becomes a link.
        assertEquals(
                "<a href=\"https://www.wikidata.org/wiki/Q103916\">Academy Award for Best Actor</a>",
                WikidataLinks.linkHtml("Q103916", "Academy Award for Best Actor"));
    }

    @Test void linkHtmlFallsBackToEscapedLabelWhenNotLinkable() {
        // No usable id → still safe, no anchor, label HTML-escaped.
        String h = WikidataLinks.linkHtml("", "A & B <x>");
        assertFalse(h.contains("<a "), h);
        assertEquals("A &amp; B &lt;x&gt;", h);
    }

    @Test void escapesPlainText() {
        assertEquals("a &amp; b", WikidataLinks.html("a & b"));
    }

    @Test void linkedRecordRendersItsOwnHtml() {
        assertEquals(WikidataLinks.linkHtml("P57", "director"),
                WikidataLinks.of("P57", "director").html());
    }
}
