package quiz.transform.ui;

import objectview.field.FieldPath;
import org.junit.jupiter.api.Test;
import quiz.curation.Correction;
import quiz.curation.CorrectionSource;
import quiz.curation.Corrections;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import domain.DomainModel;

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

        Corrections.apply(List.of(composer), List.of(source(
                Correction.entityLabel("Q95709545", "Mary Ramos", "wikidata"))));

        assertEquals("Mary Ramos", composer.getDisplayName());
    }

    /** One correction, every referrer fixed — because they share the instance. */
    @Test void everyReferenceToThatEntityShowsTheNewName() {
        WikidataDynamicObject composer =
                new WikidataDynamicObject("Q95709545", "Q95709545");
        WikidataDynamicObject filmA = film("Q1", "Django Unchained", composer);
        WikidataDynamicObject filmB = film("Q2", "Kill Bill", composer);

        assertTrue(FieldCoverageColumns.hasUnnamedReferenceInstance(
                filmA, FieldPath.parse("composer")), "unnamed before");

        Corrections.apply(List.of(composer, filmA, filmB), List.of(source(
                Correction.entityLabel("Q95709545", "Mary Ramos", "wikidata"))));

        assertFalse(FieldCoverageColumns.hasUnnamedReferenceInstance(
                filmA, FieldPath.parse("composer")));
        assertFalse(FieldCoverageColumns.hasUnnamedReferenceInstance(
                filmB, FieldPath.parse("composer")),
                    "the second film was never curated, and is fixed anyway");
    }

    /**
     * Identity is ⟨typeKey, qid⟩, so one QID can be pooled under two type keys — the
     * same entity stamped as a class by one run and left an untyped leaf by another. A
     * label is a statement about the ENTITY, so it must reach both; keeping only the
     * first renamed one and left the other a bare QID, chosen by iteration order.
     */
    @Test void anUntypedLabelReachesEveryInstanceSharingTheQid() {
        WikidataDynamicObject stamped =
                new WikidataDynamicObject("Q95709545", "Q95709545");
        stamped.type("Person");
        WikidataDynamicObject leaf =
                new WikidataDynamicObject("Q95709545", "Q95709545");

        Corrections.apply(List.of(stamped, leaf), List.of(source(
                Correction.entityLabel("Q95709545", "Mary Ramos", "wikidata"))));

        assertEquals("Mary Ramos", stamped.getDisplayName());
        assertEquals("Mary Ramos", leaf.getDisplayName(),
                     "the second instance of the same entity must not stay a bare QID");
    }

    /** A TYPED correction still names exactly one instance: ⟨typeKey, qid⟩ is the
     *  identity, and widening that would let one class's value overwrite another's. */
    @Test void aTypedCorrectionStillReachesOnlyItsOwnInstance() {
        WikidataDynamicObject person =
                new WikidataDynamicObject("Q95709545", "Q95709545");
        person.type("Person");
        WikidataDynamicObject other =
                new WikidataDynamicObject("Q95709545", "Q95709545");
        other.type("Organisation");

        Corrections.apply(List.of(person, other), List.of(source(new Correction(
                "Person", "Q95709545",
                objectview.field.ViewableContractFieldSet.DISPLAY_KEY, "Mary Ramos",
                "wikidata", null, quiz.curation.CorrectionPolicy.REPLACE, null))));

        assertEquals("Mary Ramos", person.getDisplayName());
        assertEquals("Q95709545", other.getDisplayName(),
                     "a correction scoped to a type must not touch another type");
    }

    /**
     * After the product compiler a bare reference is no longer an instance: with no
     * class in the model and no fields of its own it collapses to its DISPLAY NAME, so
     * an entity that never got a label collapses to the QID that stood in for one. The
     * detection has to see that shape too, or it reports zero on exactly the domain the
     * curation UI opens — which is what it did.
     */
    @Test void aCollapsedReferenceIsRecognisedByItsQidShapedValue() {
        WikidataDynamicObject film = new WikidataDynamicObject("Q1", "12 Angry Men");
        film.type("Movies");
        film.merge("composer", "Q592174");           // collapsed, never labelled

        WikidataDynamicObject named = new WikidataDynamicObject("Q2", "The Fellowship");
        named.type("Movies");
        named.merge("composer", "Howard Shore");     // collapsed, labelled

        assertTrue(FieldCoverageColumns.hasUnnamedReference(
                film, FieldPath.parse("composer"), true));
        assertFalse(FieldCoverageColumns.hasUnnamedReferenceInstance(
                named, FieldPath.parse("composer")),
                    "a real label is not a QID, so it must not be flagged");
        assertEquals(java.util.Set.of("Q592174"),
                     FieldCoverageColumns.unnamedReferences(
                             film, FieldPath.parse("composer"), true),
                     "the QID is what a label correction is recorded against");
    }

    @Test void aQidShapedOrdinaryStringIsNotAnUnnamedReference() {
        WikidataDynamicObject record = new WikidataDynamicObject("Q1", "Record");
        record.merge("catalogCode", "Q592174");

        assertFalse(FieldCoverageColumns.hasUnnamedReference(
                record, FieldPath.parse("catalogCode"), false));
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
