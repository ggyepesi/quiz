package pageviews.discovery;

import datasource.pageviews.PageviewsClient;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import work.QueryContext;

import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Discovery reads views through the same client as generation and changes nothing. */
class PageviewsDiscoveryQueryTest {

    private static final PageviewsClient.Window OCTOBER =
            new PageviewsClient.Window(YearMonth.of(2026, 10), YearMonth.of(2026, 10));

    @Test void theMostReadArticleComesFirstAndAnUnrecordedOneHasNoValue()
            throws Exception {
        WikidataDynamicObject ptah = deity("Q134194", "Ptah");
        WikidataDynamicObject ra = deity("Q12236", "Ra");
        WikidataDynamicObject obscure = deity("Q1", "Obscure");
        PageviewsClient client = new PageviewsClient(uri -> {
            String path = uri.getRawPath();
            if (path.contains("/Ptah/")) return "{\"items\":[{\"views\":160}]}";
            if (path.contains("/Ra/")) return "{\"items\":[{\"views\":570}]}";
            return null;
        });

        List<ArticleViews> rows = new PageviewsDiscoveryQuery(
                List.of(ptah, ra, obscure), OCTOBER, client).execute(new QueryContext());

        assertEquals(List.of("Ra", "Ptah", "Obscure"),
                rows.stream().map(ArticleViews::article).toList());
        assertEquals(570L, rows.getFirst().views());
        assertNull(rows.getLast().views());
        assertEquals("No record for this title in the window", rows.getLast().outcome());
        assertNull(ra.get("views"), "discovery never writes a value onto the instance");
    }

    @Test void aFailedRequestIsPartialAndCannotMasqueradeAsNoRecord() throws Exception {
        WikidataDynamicObject ra = deity("Q12236", "Ra");
        WikidataDynamicObject ptah = deity("Q134194", "Ptah");
        PageviewsClient client = new PageviewsClient(uri -> {
            if (uri.getRawPath().contains("/Ra/")) throw new java.io.IOException("HTTP 503");
            return "{\"items\":[{\"views\":160}]}";
        });
        PageviewsDiscoveryQuery query = new PageviewsDiscoveryQuery(
                List.of(ra, ptah), OCTOBER, client);

        List<ArticleViews> rows = query.execute(new QueryContext());

        assertEquals(work.QueryStatus.PARTIAL, query.status(rows));
        assertEquals(1, rows.stream().filter(ArticleViews::failed).count());
        assertTrue(query.summary(rows).contains("1 request(s) failed"), query.summary(rows));
        assertTrue(rows.stream().anyMatch(row -> row.outcome().equals("Failed: HTTP 503")));
    }

    private static WikidataDynamicObject deity(String qid, String title) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, title);
        value.type("EgyptianDeity");
        value.enwikiTitle(title);
        return value;
    }
}
