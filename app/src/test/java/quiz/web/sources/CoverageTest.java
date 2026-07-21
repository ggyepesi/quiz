package quiz.web.sources;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoverageTest {

    /** Per-field coverage counts present vs. missing, and resolves nested paths
     *  (forWork.genre) like the ceremony/genre decomposition. */
    @Test void countsPresentAndMissingIncludingNestedPaths() {
        WikidataDynamicObject work = new WikidataDynamicObject("Q7", "The Iron Lady");
        work.put("genre", "drama film");
        WikidataDynamicObject shortFilm = new WikidataDynamicObject("Q8", "A Short");
        // no genre on the short — an expected-missing gap

        WikidataDynamicObject n1 = new WikidataDynamicObject("Q1$s1", "N1");
        n1.type("Nomination");
        n1.put("forWork", work);
        n1.put("ceremony", new WikidataDynamicObject("Q100", "96th Academy Awards"));

        WikidataDynamicObject n2 = new WikidataDynamicObject("Q2$s1", "N2");
        n2.type("Nomination");
        n2.put("forWork", shortFilm);   // work present, but no genre and no ceremony

        List<Dimension> dims = List.of(
                new Dimension("ceremony", "ceremony", Dimension.Kind.REFERENCE),
                new Dimension("genre", "forWork.genre", Dimension.Kind.VALUE),
                new Dimension("forWork", "forWork", Dimension.Kind.REFERENCE));

        List<Coverage.FieldCoverage> cov = Coverage.of(List.of(n1, n2), dims);

        Coverage.FieldCoverage ceremony = byLabel(cov, "ceremony");
        assertEquals(1, ceremony.present());
        assertEquals(2, ceremony.total());
        assertEquals(1, ceremony.missing());

        Coverage.FieldCoverage genre = byLabel(cov, "genre");
        assertEquals(1, genre.present());   // only work Q7 has a genre
        assertEquals(2, genre.total());

        // forWork is present on both (even the one whose work has no genre).
        assertEquals(2, byLabel(cov, "forWork").present());
    }

    private static Coverage.FieldCoverage byLabel(
            List<Coverage.FieldCoverage> cov, String label) {
        return cov.stream().filter(c -> c.label().equals(label)).findFirst().orElseThrow();
    }
}
