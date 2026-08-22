package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reported shape: sampling films for a location field listed "English-language films"
 * and "American films" first and left "Films set in Sierra Leone" at the bottom — the one
 * category that actually names a field value.
 *
 * <p>Ranking by coverage was exactly backwards. A category every sampled article carries
 * describes the SAMPLE, not the member, so it cannot be naming something that varies per
 * member; the fewer articles share it, the more it says about those articles specifically.
 */
class DiscoverWikipediaCategoriesRankingTest {

    @Test void theCategoryThatNamesAValueOutranksTheOnesEveryFilmCarries() {
        List<List<Object>> rows = rows(
                List.of("English-language films", 12, "Blood Diamond"),
                List.of("American films", 10, "Blood Diamond"),
                List.of("Films set in Sierra Leone", 1, "Blood Diamond"),
                List.of("2000s war films", 3, "Blood Diamond"));

        DiscoverWikipediaCategoriesQuery.rankByDistinctiveness(rows);

        assertEquals(List.of("Films set in Sierra Leone", "2000s war films",
                        "American films", "English-language films"),
                rows.stream().map(row -> String.valueOf(row.get(0))).toList());
    }

    /** Nothing is hidden — a common category is one scroll away for a field that wants one. */
    @Test void rankingKeepsEveryObservedCategory() {
        List<List<Object>> rows = rows(
                List.of("English-language films", 12, ""),
                List.of("Films set in Sierra Leone", 1, ""));

        DiscoverWikipediaCategoriesQuery.rankByDistinctiveness(rows);

        assertEquals(2, rows.size());
    }

    /**
     * With one seed every category is shared by everything read, so there is nothing to
     * discriminate. Alphabetical is the honest answer; inventing an order would look
     * confident about a question the sample cannot answer.
     */
    @Test void aSingleSeedFallsBackToAlphabeticalRatherThanAConfidentGuess() {
        List<List<Object>> rows = rows(
                List.of("Films set in Sierra Leone", 1, "Blood Diamond"),
                List.of("2006 films", 1, "Blood Diamond"),
                List.of("American films", 1, "Blood Diamond"));

        DiscoverWikipediaCategoriesQuery.rankByDistinctiveness(rows);

        assertEquals(List.of("2006 films", "American films", "Films set in Sierra Leone"),
                rows.stream().map(row -> String.valueOf(row.get(0))).toList());
    }

    @SafeVarargs
    private static List<List<Object>> rows(List<Object>... values) {
        return new ArrayList<>(List.of(values));
    }
}
