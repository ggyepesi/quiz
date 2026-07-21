package quiz.web.sources;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.Map;

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
        // ceremony REQUIRED (missing => a VIOLATION), genre EXPECTED (missing => a GAP),
        // forWork REQUIRED but fully present (=> OK).
        Map<String, String> exp = Map.of(
                "ceremony", "REQUIRED", "forWork.genre", "EXPECTED", "forWork", "REQUIRED");

        List<Coverage.FieldCoverage> cov = Coverage.of(List.of(n1, n2), dims, exp);

        Coverage.FieldCoverage ceremony = byLabel(cov, "ceremony");
        assertEquals(1, ceremony.present());
        assertEquals(2, ceremony.total());
        assertEquals(1, ceremony.missing());
        assertEquals("VIOLATION", ceremony.verdict());   // REQUIRED + missing

        Coverage.FieldCoverage genre = byLabel(cov, "genre");
        assertEquals(1, genre.present());   // only work Q7 has a genre
        assertEquals(2, genre.total());
        assertEquals("GAP", genre.verdict());            // EXPECTED + missing

        // forWork is present on both -> OK despite being REQUIRED.
        Coverage.FieldCoverage forWork = byLabel(cov, "forWork");
        assertEquals(2, forWork.present());
        assertEquals("OK", forWork.verdict());
    }

    private static Coverage.FieldCoverage byLabel(
            List<Coverage.FieldCoverage> cov, String label) {
        return cov.stream().filter(c -> c.label().equals(label)).findFirst().orElseThrow();
    }
}
