package wikipedia;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import work.QueryContext;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Wikisource page is read through the same MediaWiki parse as a Wikipedia article; the
 * site names the host requested and the source recorded on the retained document, so a
 * story's provenance says Wikisource and links to its page there.
 */
class WikisourcePageTest {

    static final String GYLFAGINNING = "The Prose Edda (1916 translation by Arthur Gilchrist Brodeur)/Gylfaginning";

    /** The rendered shape of Brodeur's Gylfaginning: each chapter opens with a bold numeral. */
    static final String CHAPTERS_HTML = """
            <p><b>VI.</b> Then said Gangleri: "Whence came the sons of Burr?" Burr had three sons:
            the first was Odin, the second Vili, the third Vé.</p>
            <p>And this is my belief, that he, Odin, with his brothers, must be ruler of heaven.</p>
            <p><b>XV.</b> Under that root which turns toward the Rime-Giants is Mímir's Well. Thither came
            Allfather and craved one drink of the well; but he got it not until he had laid his eye in pledge.</p>
            <p><b>L.</b> Now Loki was taken truceless. Then were taken Loki's sons, Vali and Nari or Narfi.</p>
            """;

    static String response(String title, String html) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("parse", Map.of(
                "title", title, "revid", 13093210, "text", html, "categories", List.of())));
    }

    @Test void aWikisourcePageIsRequestedAndRecordedAsWikisource() throws Exception {
        List<URI> requests = new ArrayList<>();
        var client = new WikipediaArticleClient(uri -> {
            requests.add(uri);
            return response(GYLFAGINNING, CHAPTERS_HTML);
        });

        var page = client.structuredByTitle(WikipediaArticleClient.Site.WIKISOURCE, GYLFAGINNING)
                .execute(new QueryContext());

        assertEquals("en.wikisource.org", requests.getFirst().getHost());
        var document = page.article().document();
        assertEquals("Wikisource (English)", document.kind());
        assertEquals("https://en.wikisource.org/wiki/The_Prose_Edda_%281916_translation_by_Arthur_Gilchrist_Brodeur%29/Gylfaginning",
                document.url());
        assertTrue(page.article().text().contains("laid his eye in pledge"));
    }

    @Test void wikipediaRemainsTheDefaultSite() throws Exception {
        List<URI> requests = new ArrayList<>();
        var client = new WikipediaArticleClient(uri -> {
            requests.add(uri);
            return response("Odin", "<p>Odin is a god.</p>");
        });

        var page = client.structuredByTitle("Odin").execute(new QueryContext());

        assertEquals("en.wikipedia.org", requests.getFirst().getHost());
        assertEquals("Wikipedia (English)", page.article().document().kind());
    }
}
