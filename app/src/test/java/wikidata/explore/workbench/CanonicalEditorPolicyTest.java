package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.ClassKind;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalEditorPolicyTest {

    @Test void sourceClassesMayComposeNamesWithoutAcquiringFieldIdentity() {
        CanonicalSpec spec = CanonicalEditorPolicy.spec(
                ClassKind.SOURCE, CanonicalSpec.DisplayNameMode.TEMPLATE,
                "", "{title}", "en", "title");

        assertEquals(CanonicalSpec.DisplayNameMode.TEMPLATE, spec.displayNameMode());
        assertEquals("{title}", spec.displayNameTemplate());
        assertTrue(spec.keyFields().isEmpty());
    }

    @Test void onlyStatementsEditCanonicalKeys() {
        assertTrue(CanonicalEditorPolicy.editsCanonicalKey(ClassKind.STATEMENT));
        assertFalse(CanonicalEditorPolicy.editsCanonicalKey(ClassKind.SOURCE));
        assertFalse(CanonicalEditorPolicy.editsCanonicalKey(ClassKind.OWNED));

        CanonicalSpec statement = CanonicalEditorPolicy.spec(
                ClassKind.STATEMENT, CanonicalSpec.DisplayNameMode.FIELD,
                "nominee", "", "en", "nominee category nominee");
        CanonicalSpec owned = CanonicalEditorPolicy.spec(
                ClassKind.OWNED, CanonicalSpec.DisplayNameMode.TEMPLATE,
                "", "{familyName}", "en", "familyName");

        assertEquals(java.util.List.of("nominee", "category"), statement.keyFields());
        assertTrue(owned.keyFields().isEmpty());
    }
}
