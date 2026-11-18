package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;
import wikidata.explore.query.core.WikidataAccess;
import wikidata.explore.query.result.MembershipTypeCandidate;
import wikidata.explore.query.result.WikidataTextSearchResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchWikidataTextQueryTest {

    @Test void exactTextSearchReturnsCandidatesWithoutMakingAMembershipDecision()
            throws Exception {
        RecordingApi api = new RecordingApi();

        SearchWikidataTextQuery query = new SearchWikidataTextQuery("mythical event");
        WikidataTextSearchResult result = query.execute(
                WikidataAccess.of(null, api).bind());

        assertEquals("mythical event", api.phrase);
        assertEquals(SearchWikidataTextQuery.RESULT_LIMIT, api.limit);
        assertEquals(5_000, result.totalHits());
        assertEquals("first 2 of 5000", result.countText());
        assertEquals(List.of("Q105821782", "Q28019644"), result.candidates().stream()
                .map(MembershipTypeCandidate.Member::qid).toList());
        assertTrue(query.description().contains("does not establish class membership"));
    }

    private static final class RecordingApi extends WikidataApiClient {
        private String phrase;
        private int limit;

        private RecordingApi() { super("SearchWikidataTextQueryTest/1.0"); }

        @Override public EntityTextSearchResult searchEntityText(String phrase, int limit) {
            this.phrase = phrase;
            this.limit = limit;
            return new EntityTextSearchResult(5_000, List.of(
                    new SearchResult("Q105821782", "Fall of Troy",
                            "mythical event, part of Trojan War", ""),
                    new SearchResult("Q28019644", "Diana and Callisto",
                            "mythical event in Greek mythology", "")));
        }
    }
}
