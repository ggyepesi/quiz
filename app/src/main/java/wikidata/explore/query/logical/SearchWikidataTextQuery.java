package wikidata.explore.query.logical;

import wikidata.api.WikidataApiClient;
import wikidata.explore.query.core.WikidataAccess;
import wikidata.explore.query.result.MembershipTypeCandidate;
import wikidata.explore.query.result.WikidataTextSearchResult;
import work.Query;
import work.QueryContext;

import java.util.List;
import java.util.Map;

/** Bounded exact-phrase discovery over Wikidata's indexed entity text. */
public final class SearchWikidataTextQuery
        implements Query<WikidataTextSearchResult> {

    public static final int RESULT_LIMIT = 50;
    private final String phrase;

    public SearchWikidataTextQuery(String phrase) {
        this.phrase = phrase == null ? "" : phrase.strip();
        if (this.phrase.isBlank()) {
            throw new IllegalArgumentException("Enter an indexed Wikidata entity-text phrase.");
        }
    }

    @Override public String purpose() { return "Find Wikidata candidates by text"; }
    @Override public String queryType() { return "API"; }
    @Override public String skeleton() {
        return "exact phrase -> indexed Wikidata text -> candidate QIDs";
    }
    @Override public String description() {
        return "Read-only candidate discovery; a text match does not establish class "
                + "membership.";
    }
    @Override public Map<String, String> parameters() {
        return Map.of("phrase", phrase, "limit", Integer.toString(RESULT_LIMIT));
    }

    @Override public WikidataTextSearchResult execute(QueryContext context)
            throws Exception {
        return context.step("Exact Wikidata text: " + phrase, queryType(), skeleton(),
                parameters(), step -> {
                    WikidataApiClient api = WikidataAccess.api(context);
                    api.log(line -> {
                        if (line == null) return;
                        int get = line.indexOf("] GET ");
                        if (get >= 0) step.request(line.substring(get + 6).strip());
                    });
                    try {
                        WikidataApiClient.EntityTextSearchResult response = api
                                .searchEntityText(phrase, RESULT_LIMIT);
                        List<MembershipTypeCandidate.Member> candidates = response.hits().stream()
                                .map(hit -> new MembershipTypeCandidate.Member(
                                        hit.qid(), hit.label(), hit.description()))
                                .toList();
                        WikidataTextSearchResult result = new WikidataTextSearchResult(
                                response.totalHits(), candidates);
                        step.summary(result.countText() + " candidate entity/entities");
                        return result;
                    } finally {
                        api.log(null);
                    }
                });
    }

    @Override public int rowCount(WikidataTextSearchResult result) {
        return result == null ? 0 : result.candidates().size();
    }

    @Override public String summary(WikidataTextSearchResult result) {
        return result == null ? "0 candidate entity/entities"
                : result.countText() + " candidate entity/entities";
    }
}
