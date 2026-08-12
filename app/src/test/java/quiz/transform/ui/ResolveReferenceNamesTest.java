package quiz.transform.ui;

import objectview.field.FieldPath;
import org.junit.jupiter.api.Test;
import quiz.curation.Correction;
import quiz.curation.CorrectionPolicy;
import quiz.curation.CorrectionSource;
import quiz.curation.Corrections;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A resolved name is recorded on the ENTITY, and it fixes every reference to it.
 *
 * <p>Mary Ramos rendered as Q95709545 wherever she appeared. The name belongs to her, not
 * to any one film pointing at her — writing it per referring instance would store the
 * same fact once per film and still leave her unnamed everywhere else.
 */
class ResolveReferenceNamesTest {

    @Test void aNameCorrectionRenamesTheEntityItself() {
        WikidataDynamicObject composer =
                new WikidataDynamicObject("Q95709545", "Q95709545");
        composer.type("Person");

        Corrections.apply(List.of(composer), List.of(source(new Correction(
                "Person", "Q95709545",
                objectview.field.ViewableContractFieldSet.DISPLAY_KEY, "Mary Ramos",
                "wikidata", null, CorrectionPolicy.REPLACE, null))));

        assertEquals("Mary Ramos", composer.getDisplayName());
    }

    /** One correction, every referrer fixed — because they share the instance. */
    @Test void everyReferenceToThatEntityShowsTheNewName() {
        WikidataDynamicObject composer =
                new WikidataDynamicObject("Q95709545", "Q95709545");
        composer.type("Person");
        WikidataDynamicObject filmA = film("Q1", "Django Unchained", composer);
        WikidataDynamicObject filmB = film("Q2", "Kill Bill", composer);

        assertTrue(FieldCoverageColumns.hasUnnamedReference(
                filmA, FieldPath.parse("composer")), "unnamed before");

        Corrections.apply(List.of(composer, filmA, filmB), List.of(source(new Correction(
                "Person", "Q95709545",
                objectview.field.ViewableContractFieldSet.DISPLAY_KEY, "Mary Ramos",
                "wikidata", null, CorrectionPolicy.REPLACE, null))));

        assertFalse(FieldCoverageColumns.hasUnnamedReference(
                filmA, FieldPath.parse("composer")));
        assertFalse(FieldCoverageColumns.hasUnnamedReference(
                filmB, FieldPath.parse("composer")),
                    "the second film was never curated, and is fixed anyway");
    }

    /** The coverage column counts members whose reference is unnamed, so a field full of
     *  bare QIDs is visible without drilling it. */
    @Test void theUnnamedCountIsVisiblePerField() {
        WikidataDynamicObject unnamed =
                new WikidataDynamicObject("Q95709545", "Q95709545");
        WikidataDynamicObject named = new WikidataDynamicObject("Q207773", "Howard Shore");
        WikidataDynamicObject bad = film("Q1", "Django Unchained", unnamed);
        WikidataDynamicObject good = film("Q2", "The Fellowship", named);
        List<WikidataDynamicObject> all = List.of(bad, good);

        FieldCoverageColumns columns = new FieldCoverageColumns(
                new TestDomain(all), () -> "Movies", () -> all);
        FieldCoverageColumns.Coverage coverage =
                columns.coverage(FieldPath.parse("composer"));

        assertEquals(2, coverage.present(), "both films HAVE a composer");
        assertEquals(1, coverage.unnamed(), "only one of them can be read");
    }

    private static WikidataDynamicObject film(
            String qid, String title, WikidataDynamicObject composer) {
        WikidataDynamicObject f = new WikidataDynamicObject(qid, title);
        f.type("Movies");
        f.merge("composer", composer);
        return f;
    }

    private static CorrectionSource source(Correction correction) {
        return () -> List.of(correction);
    }

    private record TestDomain(List<? extends objectview.Viewable> values)
            implements DomainModel {
        @Override public List<String> types() { return List.of("Movies"); }
        @Override public objectview.field.FieldSchema fieldSchema(String type) {
            return List::of;
        }
        @Override public java.util.Collection<? extends objectview.Viewable> instances() {
            return values;
        }
        @Override public Class<? extends objectview.Viewable> universe() {
            return WikidataDynamicObject.class;
        }
    }
}
