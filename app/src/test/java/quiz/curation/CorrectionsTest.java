package quiz.curation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import objectview.field.FieldAccess;
import wikidata.explore.extract.WikidataDynamicObject;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The overlay: manual overrides, rules/external only fill absent fields, and the
 *  sidecar round-trips so it re-applies after a regeneration. */
class CorrectionsTest {

    private static int intOf(Object v) {
        return ((Number) v).intValue();
    }

    @Test void manualOverridesAndSourcesOnlyFillAbsent() {
        WikidataDynamicObject a = new WikidataDynamicObject("Q1", "Cassandra");
        a.type("Nomination");                       // no year — the gap
        WikidataDynamicObject b = new WikidataDynamicObject("Q2", "Other");
        b.type("Nomination");
        b.put("year", 2000);                        // real base data

        CorrectionSource manual = () -> List.of(
                new Correction("Q1", "year", 2026, Correction.MANUAL));
        CorrectionSource external = () -> List.of(
                new Correction("Q1", "year", 1999, "wikipedia"),   // loses to manual
                new Correction("Q2", "year", 1888, "wikipedia"));  // must not clobber Q2

        int applied = Corrections.apply(List.of(a, b), List.of(manual, external));

        assertEquals(1, applied, "only the manual gap-fill was applied");
        assertEquals(2026, intOf(FieldAccess.getPath(a, "year")), "manual fills the gap");
        assertEquals(2000, intOf(FieldAccess.getPath(b, "year")), "real data never clobbered");
    }

    @Test void externalFillsAGapWhenNoManualClaimsIt() {
        WikidataDynamicObject a = new WikidataDynamicObject("Q1", "Cassandra");
        a.type("Nomination");

        CorrectionSource external = () -> List.of(
                new Correction("Q1", "year", 1969, "wikipedia"));

        assertEquals(1, Corrections.apply(List.of(a), List.of(external)));
        assertEquals(1969, intOf(FieldAccess.getPath(a, "year")));
    }

    @Test void typedCorrectionDoesNotHitAnotherTypeWithTheSameIdentifier() {
        WikidataDynamicObject nomination = new WikidataDynamicObject("Q1", "Nomination");
        nomination.type("Nomination");
        WikidataDynamicObject motivation = new WikidataDynamicObject("Q1", "Motivation");
        motivation.type("Motivation");

        CorrectionSource source = () -> List.of(new Correction(
                "Motivation", "Q1", "year", 1969, "wikipedia", null));

        assertEquals(1, Corrections.apply(List.of(nomination, motivation), List.of(source)));
        assertEquals(null, FieldAccess.getPath(nomination, "year"));
        assertEquals(1969, intOf(FieldAccess.getPath(motivation, "year")));
    }

    @Test void manualCurationRoundTrips(@TempDir Path dir) throws Exception {
        File f = new File(dir.toFile(), "oscarnominations.curation.json");
        ManualCuration c = new ManualCuration(f);
        c.put("Q1", "year", 2026);
        c.put("Q1", "year", 2027);   // replaces, not appends
        c.save();

        ManualCuration reloaded = new ManualCuration(f).load();
        assertEquals(1, reloaded.corrections().size());
        Correction only = reloaded.corrections().get(0);
        assertEquals("Q1", only.qid());
        assertEquals("year", only.field());
        assertEquals(2027, intOf(only.value()));
        assertTrue(only.isManual());
    }

    @Test void typedMediaMetadataRoundTrips(@TempDir Path dir) throws Exception {
        File f = new File(dir.toFile(), "countries.curation.json");
        ManualCuration c = new ManualCuration(f);
        c.put("Country", "Q1", "flags", "https://example.test/flag.svg",
                "dbpedia", Correction.MEDIA_COLLECTION);
        c.save();

        Correction loaded = new ManualCuration(f).load().corrections().get(0);
        assertEquals("Country", loaded.type());
        assertEquals(Correction.MEDIA_COLLECTION, loaded.valueKind());
    }

    @Test void approvedIdentityLinkRoundTrips(@TempDir Path dir) throws Exception {
        File f = new File(dir.toFile(), "people.curation.json");
        ManualCuration c = new ManualCuration(f);
        c.putIdentityLink(new IdentityLink(
                "Person", "421", "NobelPrize.org", "421",
                "https://www.nobelprize.org/prizes/medicine/1980/snell/facts/",
                "George Davis Snell", "nobelprize.org"));
        c.save();

        IdentityLink loaded = new ManualCuration(f).load().identityLinks().get(0);
        assertEquals("Person", loaded.type());
        assertEquals("George Davis Snell", loaded.canonicalName());
        assertEquals("421", loaded.sourceId());
    }
}
