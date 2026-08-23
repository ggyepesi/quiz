package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a class is built decides what makes two of its instances the same instance, so a
 * second discriminator could only agree with the first or be wrong.
 *
 * <p>There was one. {@code CanonicalSpec.Kind} was chosen separately in the editor, set
 * to DERIVED by three places that all keyed on the class reifying statements, and
 * corrected by hand at the single point that asked whether an instance has an identity of
 * its own — because a part borrows its owner's QID and so looked like an entity to
 * anything reading the id alone.
 */
class IdentityRegimeFollowsClassKindTest {

    @Test void onlyASourceClassTakesItsIdentityFromItsSource() {
        assertTrue(ClassKind.SOURCE.identityFromSource());
        assertFalse(ClassKind.STATEMENT.identityFromSource(),
                "a reified record is identified by the statement or a declared key");
        assertFalse(ClassKind.OWNED.identityFromSource(),
                "a part is identified by its owner and the site that produced it — "
                        + "borrowing the owner's QID is how its fields load, not what "
                        + "makes it itself");
        assertTrue(ClassKind.STATEMENT.usesCanonicalKey());
        assertFalse(ClassKind.OWNED.usesCanonicalKey());
        assertTrue(ClassKind.OWNED.identityFromOwner());
    }

    @Test void ownedClassesAreNotAskedForStatementKeysByValidation() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel owned = new GeneratedClassModel("Name");
        owned.classKind(ClassKind.OWNED);
        owned.canonical().displayNameMode(CanonicalSpec.DisplayNameMode.TEMPLATE)
                .displayNameTemplate("name");
        project.addClass(owned);

        String report = GeneratedProjectModelValidator.validate(project).format();

        assertFalse(report.contains("canonical key"), report);
    }

    @Test void assigningAStatementSourceIsWhatChangesTheRegime() {
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        assertEquals(ClassKind.SOURCE, nomination.classKind());

        nomination.statementSource(new StatementClassSource("Member", "P1411"));

        assertEquals(ClassKind.STATEMENT, nomination.classKind(),
                "one act, one consequence — nothing else to keep in step with it");
    }

    @Test void anIdentifierFollowsTheRegimeRatherThanTheSpec() {
        CanonicalSpec spec = new CanonicalSpec();
        spec.keyFields().add("nominee");
        Canonicalizer.FieldReader reader = field ->
                "nominee".equals(field) ? "Q1" : null;

        assertEquals("Q42", Canonicalizer.identifier(
                        ClassKind.SOURCE, spec, reader, "Q42", "fallback"),
                "a source-identified class keeps its id even where a key is configured");
        assertEquals("Q1", Canonicalizer.identifier(
                        ClassKind.STATEMENT, spec, reader, "Q42", "fallback"),
                "and one that derives its identity joins the key fields");
    }

    @Test void aDisplayNameIsComposedWhenTheMODEsaysSoAndNotOtherwise() {
        // The bug the conflation hid: the composers guarded on the identity regime as
        // well as the display mode, so a source-identified class configured with a
        // template silently kept its source label instead.
        CanonicalSpec spec = new CanonicalSpec()
                .displayNameMode(CanonicalSpec.DisplayNameMode.TEMPLATE)
                .displayNameTemplate("{a} · {b}");
        Canonicalizer.FieldReader reader = field -> switch (field) {
            case "a" -> "one";
            case "b" -> "two";
            default -> null;
        };

        assertEquals("one · two", Canonicalizer.displayName(spec, reader, "fallback"));
    }
}
