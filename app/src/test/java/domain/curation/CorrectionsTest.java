package domain.curation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import objectview.field.FieldAccess;
import domain.runtime.WikidataDynamicObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test void aTypedReferenceSelectsItsClassWhenTheIdentifierIsShared() {
        WikidataDynamicObject group = new WikidataDynamicObject("Q9133532", "Assessors");
        group.type("DeityGroup");
        domain.transform.DynamicViewable stale =
                new domain.transform.DynamicViewable("row-1", "row-1");
        WikidataDynamicObject assessor =
                new WikidataDynamicObject("row-1", "First assessor");
        assessor.type("AssessorOfMaat");
        CorrectionSource source = () -> List.of(new Correction(
                "DeityGroup", "Q9133532", "members", "row-1", "wikipedia",
                Correction.REFERENCE_COLLECTION, CorrectionPolicy.ADD_TO_COLLECTION,
                null, "AssessorOfMaat"));

        assertEquals(1, Corrections.apply(
                List.of(group, stale, assessor), List.of(source)));
        assertEquals(List.of(assessor), group.get("members"),
                "the earlier generic same-ID object must not capture the reference");
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
        assertFalse(Files.readString(f.toPath()).contains("referenceType"),
                "untyped corrections must not add empty metadata to every sidecar entry");
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

    @Test void typedReferenceMetadataRoundTrips(@TempDir Path dir) throws Exception {
        File file = dir.resolve("egyptiangods.curation.json").toFile();
        ManualCuration curation = new ManualCuration(file);
        curation.put("DeityGroup", "Q9133532", "members", "row-1", "wikipedia",
                Correction.REFERENCE_COLLECTION, CorrectionPolicy.ADD_TO_COLLECTION,
                null, "AssessorOfMaat");
        curation.save();

        Correction loaded = new ManualCuration(file).load().corrections().getFirst();
        assertEquals("AssessorOfMaat", loaded.referenceType());
    }

    @Test void sourcePropertyAndReplayPolicyRoundTrip(@TempDir Path dir) throws Exception {
        File f = new File(dir.toFile(), "countries.curation.json");
        ManualCuration c = new ManualCuration(f);
        ValueSource source = new ValueSource(
                "Wikidata", "Q133888", "P3896",
                "https://www.wikidata.org/wiki/Q133888");
        c.put("State", "Ashmore", "geoshapes", "Data:Ashmore.map",
                "wikidata", null, CorrectionPolicy.ADD_TO_COLLECTION, source);
        c.save();

        Correction loaded = new ManualCuration(f).load().corrections().get(0);
        assertEquals(CorrectionPolicy.ADD_TO_COLLECTION, loaded.policy());
        assertEquals("Q133888", loaded.source().entityId());
        assertEquals("P3896", loaded.source().propertyId());
    }

    @Test void addedFieldDefinitionRoundTrips(@TempDir Path dir) throws Exception {
        File f = new File(dir.toFile(), "states.curation.json");
        ManualCuration curation = new ManualCuration(f);
        objectview.field.FieldRef field = objectview.field.FieldRef.described(
                "maps", objectview.field.FieldKind.COLLECTION,
                objectview.field.FieldKind.MEDIA, "Collection<ImagePane>",
                false, true, null, false, false,
                true, false, "", false);
        curation.putFieldDeclaration("State", field);
        curation.save();

        FieldDeclaration loaded = new ManualCuration(f).load()
                .fieldDeclarations().get(0);
        assertEquals("State", loaded.type());
        assertTrue(loaded.collection());
        assertEquals(objectview.field.FieldKind.MEDIA, loaded.valueKind());
        assertTrue(loaded.fieldRef().inline());
    }

    @Test void addPolicyReplaysOverFreshCollectionWithoutFreezingBaseValues() {
        WikidataDynamicObject state = new WikidataDynamicObject("Q1", "State");
        state.type("State");
        state.put("aliases", new java.util.ArrayList<>(List.of("base")));
        CorrectionSource source = () -> List.of(new Correction(
                "State", "Q1", "aliases", "curated", "wikidata", null,
                CorrectionPolicy.ADD_TO_COLLECTION,
                new ValueSource("Wikidata", "Q1", "P1448", null)));

        assertEquals(1, Corrections.apply(List.of(state), List.of(source)));
        assertEquals(List.of("base", "curated"), state.get("aliases"));
    }

    @Test void applyingAnAlreadyPresentValueReportsNoChange() {
        WikidataDynamicObject state = new WikidataDynamicObject("Q1", "State");
        state.type("State");
        state.put("aliases", new java.util.ArrayList<>(List.of("curated")));
        CorrectionSource source = () -> List.of(new Correction(
                "State", "Q1", "aliases", "curated", "wikipedia", null,
                CorrectionPolicy.ADD_TO_COLLECTION, null));

        assertEquals(0, Corrections.apply(List.of(state), List.of(source)));
        assertEquals(List.of("curated"), state.get("aliases"));
    }

    @Test void evidenceOnlyDirectiveNeverChangesTheGeneratedValue() {
        WikidataDynamicObject film = new WikidataDynamicObject("Q1", "Film");
        film.type("Film");
        film.put("location", "Sierra Leone");
        CorrectionSource source = () -> List.of(new Correction(
                "Film", "Q1", "location", "Paris", "wikipedia", null,
                CorrectionPolicy.EVIDENCE_ONLY,
                new ValueSource("Wikipedia (English)", "Film", "field:location", null)));

        assertEquals(0, Corrections.apply(List.of(film), List.of(source)));
        assertEquals("Sierra Leone", film.get("location"));
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

    /** Category evidence used to be a Transform curation override. It is authored only
     * in ModelBuilder now: an old sidecar must neither shadow the model nor survive the
     * next save as apparently live configuration. */
    @Test void legacyCategoryOverridesAreRetiredAtTheSidecarBoundary(@TempDir Path dir)
            throws Exception {
        File file = dir.resolve("movies.curation.json").toFile();
        java.nio.file.Files.writeString(file.toPath(), """
                {
                  "sourceRecipes" : [ {
                    "type" : "Movies",
                    "field" : "locations",
                    "provider" : "wikipedia-category",
                    "parameters" : {
                      "pattern" : "Films set in <value>",
                      "policy" : "REVIEW"
                    }
                  } ]
                }
                """);

        ManualCuration loaded = new ManualCuration(file).load();
        assertTrue(loaded.sourceRecipes().isEmpty(),
                "a retired override must not shadow the model-authored category rule");
        loaded.save();
        assertFalse(java.nio.file.Files.readString(file.toPath())
                .contains("wikipedia-category"));
    }

    /** A reference names its class as well as its identifier. An untyped one used to be
     *  resolved to any same-identifier object, or to an invented unnamed placeholder;
     *  it is now refused where it is created or loaded, naming what is missing. */
    @Test void aReferenceCorrectionWithoutItsClassIsRefused() {
        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new Correction(
                        "Film", "Q1", "location", "Q60", "manual", Correction.REFERENCE,
                        CorrectionPolicy.REPLACE, null));
        assertTrue(refused.getMessage().contains("referenceType"), refused.getMessage());
    }

    @Test void aTypedReferenceResolvesOnlyToThatClassAndNeverInventsAMember() {
        WikidataDynamicObject film = new WikidataDynamicObject("Q1", "Film");
        film.type("Film");
        WikidataDynamicObject sameIdOtherClass = new WikidataDynamicObject("Q60", "Other");
        sameIdOtherClass.type("Person");
        Correction location = new Correction("Film", "Q1", "location", "Q60", "manual",
                Correction.REFERENCE, CorrectionPolicy.REPLACE, null, "Location");

        Corrections.apply(List.of(film, sameIdOtherClass), List.of(() -> List.of(location)));

        assertEquals(null, FieldAccess.getPath(film, objectview.field.FieldPath.of("location")),
                "no Location Q60 is loaded, so nothing is attached; neither the Person nor "
                        + "an invented placeholder");
    }
}
