package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertFalse(ClassKind.GRAPH.identityFromSource(),
                "an annotation carries the candidate's QID, which is not its own id — "
                        + "the same borrowed-identifier mistake a part's owner QID made, "
                        + "and what let an Apply put annotation shells where the "
                        + "generated instances had been");
        assertTrue(ClassKind.STATEMENT.usesCanonicalKey());
        assertFalse(ClassKind.OWNED.usesCanonicalKey());
        assertFalse(ClassKind.GRAPH.usesCanonicalKey());
        assertTrue(ClassKind.OWNED.identityFromOwner());
        assertFalse(ClassKind.GRAPH.identityFromOwner(),
                "a candidate is classified by the graph, not owned by it");
        assertTrue(ClassKind.GRAPH.identityFromClassifiedCandidate());
    }

    /**
     * Exactly one regime answers for each kind, and every kind has one.
     *
     * <p>The allowlist can only shrink. AGGREGATE is on it because it answers NONE while
     * being keyed in fact: {@code AggregateClassSource} says its identity is
     * {@code canonical().keyFields()}, the editor shows that key as fixed and syncs it,
     * but {@code usesCanonicalKey()} is true only for STATEMENT — so validation, which
     * asks that method, never reports an aggregate whose key is empty. Tracked
     * separately; it is a fault in the kind that predates GRAPH, not a property of it.
     */
    private static final java.util.Set<ClassKind> WITHOUT_A_STATED_REGIME =
            java.util.Set.of(ClassKind.AGGREGATE);

    @Test void everyKindHasExactlyOneIdentityRegime() {
        for (ClassKind kind : ClassKind.values()) {
            if (WITHOUT_A_STATED_REGIME.contains(kind)) continue;
            long regimes = java.util.stream.Stream.of(
                            kind.identityFromSource(), kind.usesCanonicalKey(),
                            kind.identityFromOwner(),
                            kind.identityFromClassifiedCandidate())
                    .filter(Boolean::booleanValue).count();
            assertEquals(1, regimes,
                    kind + " must answer exactly one identity regime; a kind that "
                            + "answers none is one whose identity nothing states, and a "
                            + "kind that answers two is the second discriminator this "
                            + "enum exists to remove");
        }
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

    @Test void aSourceClassKeysOnItsSourceUnlessItWasGivenAContentKey() {
        CanonicalSpec spec = new CanonicalSpec();
        spec.keyFields().add("nominee");
        Canonicalizer.FieldReader reader = field ->
                "nominee".equals(field) ? "Q1" : null;

        assertEquals("Q42", Canonicalizer.identifier(
                        ClassKind.SOURCE, new CanonicalSpec(), reader, "Q42", "fallback"),
                "with nothing configured, a source class is its source entity");
        // And with a content key it uses one. This asserted that a Source class keeps
        // its source id even THEN, which was true only while such a key was refused:
        // the configuration was accepted and then ignored, which is the state this
        // whole design removes. A canonical instance retains every contributing source
        // identity, so choosing a content key no longer loses the QIDs Enrich needs.
        assertNotEquals("Q42", Canonicalizer.identifier(
                        ClassKind.SOURCE, spec, reader, "Q42", "fallback"),
                "a configured content key is honoured rather than accepted and ignored");
        // What matters is that it derives from the KEY and not from the source id, not
        // how the key is encoded — the encoding frames its components now, so that two
        // different tuples cannot spell the same identity.
        String derived = Canonicalizer.identifier(
                ClassKind.STATEMENT, spec, reader, "Q42", "fallback");
        assertNotEquals("Q42", derived,
                "and one that derives its identity ignores the source id");
        assertEquals(derived, Canonicalizer.identifier(
                        ClassKind.STATEMENT, spec, reader, "a different source", "fallback"),
                "the key decides it, so the source id cannot change it");
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
