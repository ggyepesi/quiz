package quiz.transform.discovery;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import work.QueryContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikipediaArticleFetchQueryTest {

    @Test void fetchesOnlyConfirmedInstancesAndReportsMissingCorrespondence()
            throws Exception {
        WikidataDynamicObject maat = new WikidataDynamicObject("Q170471", "Maat");
        maat.enwikiTitle("Maat");
        WikidataDynamicObject unknown = new WikidataDynamicObject("Q1", "No article");
        var client = new wikipedia.WikipediaArticleClient(uri -> """
                {"parse":{"title":"Maat","revid":42,
                  "text":"<p>Maat represented <a href='/wiki/Truth' title='Truth'>truth</a>.</p><h2>Worship</h2><p>Her cult was widespread.</p>",
                  "categories":[{"category":"Egyptian_goddesses"},
                    {"category":"Hidden_tracking","hidden":true}]}}
                """);

        var results = new WikipediaArticleFetchQuery(
                List.of(maat, unknown), client).execute(new QueryContext());

        assertEquals(2, results.size());
        assertTrue(results.getFirst().available());
        assertEquals(List.of("Introduction", "Worship"),
                results.getFirst().article().sections().stream()
                        .map(datasource.evidence.WikipediaArticle.Section::heading).toList());
        assertEquals("Truth", results.getFirst().article().articleLinks()
                .getFirst().targetTitle());
        assertEquals(List.of("Egyptian goddesses"),
                results.getFirst().article().categories());
        assertFalse(results.get(1).available());
        assertTrue(results.get(1).problem().contains("No English Wikipedia article"));
    }
}
