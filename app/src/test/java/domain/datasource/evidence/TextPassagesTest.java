package domain.datasource.evidence;

import org.junit.jupiter.api.Test;
import wikipedia.WikipediaArticleClient;
import work.QueryContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing only: a page divides at the divisions its author made. Brodeur's Gylfaginning
 * numbers its chapters with a bold Roman numeral that opens a paragraph, and a chapter runs
 * on until the next numeral; a sectioned page divides at its headings.
 */
class TextPassagesTest {

    private static final String CHAPTERS_HTML = """
            <p>Gylfi was a king in Sweden.</p>
            <p><b>VI.</b> Burr had three sons: the first was Odin, the second Vili, the third Vé.</p>
            <p>And this is my belief, that he, Odin, with his brothers, must be ruler of heaven.</p>
            <p><b>XV.</b> Thither came Allfather and craved one drink of the well; but he got it not
            until he had laid his eye in pledge.</p>
            <p><b>L.</b> Now Loki was taken truceless.</p>
            """;

    private static final String SECTIONS_HTML = """
            <p>Odin is a god.</p>
            <h2>The theft of the mead</h2><p>Odin took the mead of poetry.</p>
            <h2>The eye</h2><p>Odin gave an eye at Mímir's well.</p><p>He drank and gained wisdom.</p>
            """;

    private static WikipediaArticle page(String html) throws Exception {
        String response = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                Map.of("parse", Map.of("title", "Gylfaginning", "revid", 1, "text", html,
                        "categories", List.of())));
        return new WikipediaArticleClient(uri -> response)
                .structuredByTitle(WikipediaArticleClient.Site.WIKISOURCE, "Gylfaginning")
                .execute(new QueryContext()).article().structuredArticle();
    }

    @Test void numberedChaptersRunUntilTheNextNumeralAndFrontMatterIsNotAChapter() throws Exception {
        var passages = TextPassages.divide(page(CHAPTERS_HTML), TextPassages.Division.NUMBERED_CHAPTERS);

        assertEquals(List.of("VI", "XV", "L"), passages.stream().map(TextPassages.Passage::label).toList());
        var sons = passages.getFirst().text();
        assertTrue(sons.startsWith("VI. Burr had three sons"), sons);
        assertTrue(sons.contains("ruler of heaven"), "a chapter keeps its later paragraphs");
        assertTrue(passages.get(1).text().contains("laid his eye in pledge"));
        assertTrue(passages.stream().noneMatch(p -> p.text().contains("king in Sweden")));
    }

    @Test void sectionsDivideAtHeadingsAndKeepEveryParagraphOfTheSection() throws Exception {
        var passages = TextPassages.divide(page(SECTIONS_HTML), TextPassages.Division.SECTIONS);

        assertEquals(List.of("Introduction", "The theft of the mead", "The eye"),
                passages.stream().map(TextPassages.Passage::label).toList());
        assertEquals("Odin gave an eye at Mímir's well.\n\nHe drank and gained wisdom.",
                passages.getLast().text());
    }

    /** Reported on the live Gylfaginning: XI, XXVII and XLIII began on a new scan page,
     *  whose Wikisource anchor left a zero-width space before the numeral. */
    @Test void aChapterOpeningOnANewScanPageIsStillAChapter() throws Exception {
        String html = "<p><b>X.</b> Night and day.</p><p><span><span class=\"pagenum ws-noexport\">"
                + "<span class=\"pagenum-inner ws-noexport\">\u200B</span></span></span>"
                + "<b>XI.</b> Then said Gangleri: \"How does he govern the course of the sun?\"</p>";
        var passages = TextPassages.divide(page(html), TextPassages.Division.NUMBERED_CHAPTERS);

        assertEquals(List.of("X", "XI"), passages.stream().map(TextPassages.Passage::label).toList());
        assertTrue(passages.stream().noneMatch(p -> p.text().contains("\u200B")));
    }

    @Test void positionsPointIntoTheRetainedText() throws Exception {
        var page = page(CHAPTERS_HTML);
        for (var passage : TextPassages.divide(page, TextPassages.Division.NUMBERED_CHAPTERS)) {
            assertTrue(page.text().startsWith(passage.label() + ".", passage.start()), passage.label());
        }
    }
}
