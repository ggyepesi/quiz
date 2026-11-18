package wikidata.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikidataTextSearchTest {

    @Test void userQuotesDoNotBecomeNestedSearchQuotes() {
        assertEquals("mythical event",
                WikidataApiClient.exactTextPhrase("  \"mythical event\"  "));
        assertEquals("mythical event",
                WikidataApiClient.exactTextPhrase("mythical event"));
    }

    @Test void resultKeepsTheIndexTotalWhenOnlyTheFirstPageWasFetched() {
        WikidataApiClient.EntityTextSearchResult result =
                new WikidataApiClient.EntityTextSearchResult(5_000, List.of(
                        new WikidataApiClient.SearchResult(
                                "Q1", "one", "first", "")));

        assertEquals(5_000, result.totalHits());
        assertTrue(result.capped());
    }
}
