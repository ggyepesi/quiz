package wikidata.explore.query.result;

import java.util.List;

/** Bounded text candidates together with Wikidata's complete index hit count. */
public record WikidataTextSearchResult(
        long totalHits, List<MembershipTypeCandidate.Member> candidates) {

    public WikidataTextSearchResult {
        totalHits = Math.max(0, totalHits);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public boolean capped() { return totalHits > candidates.size(); }

    public String countText() {
        return capped() ? "first " + candidates.size() + " of " + totalHits
                : Long.toString(totalHits);
    }
}
