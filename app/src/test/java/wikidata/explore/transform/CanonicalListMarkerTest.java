package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelValidator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #92: the last role a reification still INFERRED rather than read off the model.
 *
 * <p>Wikidata records a shared award on every recipient, so the same nomination arrives
 * once per endpoint; the copy carrying the full recipient LIST is the complete one and
 * the others are dropped as denormalized duplicates. Which field that is was "the first
 * multi-valued entity qualifier" — so a statement class with two of them had the answer
 * decided by field order, and reordering fields silently changed which records survived.
 */
class CanonicalListMarkerTest {

    @Test void oneCandidateNeedsNoDeclaration() {
        assertEquals("nominees",
                ModelStatementReifications.canonicalListMarker("", List.of("nominees")),
                "the inference is right whenever there is nothing to choose between");
    }

    @Test void theDeclarationDecidesWhenThereAreTwoCandidates() {
        assertEquals("nominees",
                ModelStatementReifications.canonicalListMarker(
                        "nominees", List.of("presenters", "nominees")));
    }

    @Test void theMistake_withoutADeclarationFieldOrderDecides() {
        // Same class, same two list qualifiers, different declaration order → a
        // different set of records is kept. This is why it had to become declarable.
        assertEquals("presenters",
                ModelStatementReifications.canonicalListMarker(
                        "", List.of("presenters", "nominees")));
        assertEquals("nominees",
                ModelStatementReifications.canonicalListMarker(
                        "", List.of("nominees", "presenters")));
    }

    @Test void aDeclarationThatCannotMarkAnythingDoesNotDisableCanonicalization() {
        // Falling through to the inference keeps the model working; the validator is
        // where the wrong name is reported, so it is not silent.
        assertEquals("nominees",
                ModelStatementReifications.canonicalListMarker(
                        "notAQualifier", List.of("nominees")));
    }

    @Test void noListQualifierMeansNoCanonicalCopyToPrefer() {
        assertEquals("",
                ModelStatementReifications.canonicalListMarker("", List.of()));
    }

    @Test void theValidatorRejectsAMarkerThatIsNotAMultiValuedEntityQualifier() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("oscars");
        project.rootClass(new GeneratedClassModel("Root"));

        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        GeneratedFieldModel year =
                nomination.addField("year", FieldType.DATE, FieldCardinality.SINGLE);
        year.mapping().qualifierPid("P585");
        nomination.canonical().primaryListField("year");
        project.addClass(nomination);

        String report = GeneratedProjectModelValidator.validate(project).format();

        assertTrue(report.contains("canonical copy"),
                "a scalar date cannot mark the complete copy of a statement: " + report);
    }

    @Test void theDeclarationSurvivesTheRoundTripThroughTheCompiledModel() {
        CanonicalSpec spec = new CanonicalSpec()
                .primaryListField("nominees")
                .duplicatePolicy(CanonicalSpec.DuplicatePolicy.MERGE_RECORDS);

        CanonicalSpec restored = wikidata.explore.compiled.CompiledCanonical.from(spec)
                .toSpec();
        assertEquals("nominees", restored.primaryListField(),
                "compile and decompile must not lose which copy is canonical");
        assertEquals(CanonicalSpec.DuplicatePolicy.MERGE_RECORDS,
                restored.duplicatePolicy(),
                "compile and decompile must not lose how duplicates combine");
    }
}
