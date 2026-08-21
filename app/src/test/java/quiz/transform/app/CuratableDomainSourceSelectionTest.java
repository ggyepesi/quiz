package quiz.transform.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.curation.CorrectionPolicy;
import quiz.curation.ManualCuration;
import quiz.curation.ValueSource;
import wikidata.explore.extract.WikidataDynamicObject;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuratableDomainSourceSelectionTest {
    @Test void acceptedFieldValueCanBeSelectedByItsExactSource(@TempDir Path dir) {
        WikidataDynamicObject movie = new WikidataDynamicObject("Q157058", "Blood Diamond");
        movie.type("Movie");
        ManualCuration curation = new ManualCuration(dir.resolve("movies.curation.json").toFile());
        curation.put("Movie", "Q157058", "location", "Q1044", "wikipedia-english",
                quiz.curation.Correction.REFERENCE_COLLECTION,
                CorrectionPolicy.ADD_TO_COLLECTION,
                new ValueSource("Wikipedia (English)", "Blood Diamond",
                        "field:location", "", "", "https://en.wikipedia.org/wiki/Blood_Diamond",
                        List.of()));
        CuratableDomain domain = new CuratableDomain(new SnapshotDomain(List.of(movie)), curation);
        String selection = "Source / Wikipedia (English) / Movie.location";

        assertTrue(domain.selectionNames().contains(selection));
        assertEquals(List.of(movie), domain.selectionMembers(selection));
    }
}
