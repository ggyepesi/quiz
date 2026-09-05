package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.ClassKind;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalEditorPolicyTest {

    @Test void sourceClassesMayComposeNamesWithoutAcquiringFieldIdentity() {
        CanonicalSpec spec = CanonicalEditorPolicy.spec(
                ClassKind.SOURCE, CanonicalSpec.DisplayNameMode.TEMPLATE,
                "", "{title}", null);

        assertEquals(CanonicalSpec.DisplayNameMode.TEMPLATE, spec.displayNameMode());
        assertEquals("{title}", spec.displayNameTemplate());
        assertTrue(spec.keyFields().isEmpty());
    }

    /**
     * This policy assembles a display NAME, and no longer a key.
     *
     * <p>It used to parse a space-separated string into key fields, which could not keep
     * an order — and identity joins a key's values in order, so the string form could not
     * express what an identifier is built from. ClassIdentityEditor owns the key now, as
     * an ordered list, for every construct.
     */
    @Test void theKeyIsNotAssembledHereAtAll() {
        CanonicalSpec existing = new CanonicalSpec();
        existing.keyFields().addAll(java.util.List.of("startDate", "position"));

        CanonicalSpec updated = CanonicalEditorPolicy.spec(
                ClassKind.STATEMENT, CanonicalSpec.DisplayNameMode.FIELD,
                "position", "", existing);

        assertEquals(java.util.List.of("startDate", "position"), updated.keyFields(),
                "untouched, and still in the order it was authored");
        assertEquals("position", updated.displayNameField(),
                "which is what this does assemble");
    }

    /**
     * What the editor does not touch survives it.
     *
     * <p>This built a fresh spec and the caller assigned it over the old one, so every
     * apply dropped whatever the display-name editor had no control for — including a
     * modeller's answer to "what happens when two candidates share a key", lost because
     * they edited a display name.
     */
    @Test void aReductionSurvivesAnEditThatKnowsNothingAboutIt() {
        wikidata.explore.model.CanonicalSpec existing =
                new wikidata.explore.model.CanonicalSpec();
        existing.reductions().put("spouse", canonical.Reduction.UNION_DISTINCT);
        existing.missingKeyPolicy(canonical.MissingKeyPolicy.REJECT_CANDIDATE);

        var updated = CanonicalEditorPolicy.spec(
                wikidata.explore.model.ClassKind.SOURCE,
                wikidata.explore.model.CanonicalSpec.DisplayNameMode.LABEL,
                "", "", existing);

        assertEquals(canonical.Reduction.UNION_DISTINCT,
                updated.reductions().get("spouse"));
        assertEquals(canonical.MissingKeyPolicy.REJECT_CANDIDATE,
                updated.missingKeyPolicy());
    }
}
