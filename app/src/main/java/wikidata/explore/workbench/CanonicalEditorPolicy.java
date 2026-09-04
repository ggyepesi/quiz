package wikidata.explore.workbench;

import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.ClassKind;

/** Pure policy behind the Identity & label controls; Swing only reflects it. */
final class CanonicalEditorPolicy {

    private CanonicalEditorPolicy() { }

    static boolean editsCanonicalKey(ClassKind kind) {
        return kind != null && kind.usesCanonicalKey();
    }

    static boolean hasSourceLabel(ClassKind kind) {
        return kind == ClassKind.SOURCE;
    }

    static CanonicalSpec spec(
            ClassKind kind,
            CanonicalSpec.DisplayNameMode mode,
            String displayField,
            String template,
            String language,
            CanonicalSpec existing) {
        CanonicalSpec.DisplayNameMode safeMode = mode == null
                ? CanonicalSpec.DisplayNameMode.LABEL : mode;
        // Built FROM what is there, not instead of it. This made a fresh spec and the
        // caller assigned it over the old one, so anything the editor does not touch was
        // dropped on every apply — reductions and the missing-key policy among them,
        // which is a modeller's answer to "what happens when two candidates share a key"
        // disappearing because they edited a display name.
        CanonicalSpec result = existing == null
                ? new CanonicalSpec() : existing.copy();
        result.displayNameMode(safeMode);
        switch (safeMode) {
            case LABEL -> result.labelLanguage(language);
            case FIELD -> result.displayNameField(displayField);
            case TEMPLATE -> result.displayNameTemplate(template);
        }
        // The key is not touched here at all. It belongs to ClassIdentityEditor, which
        // holds it as an ordered list — and identity joins a key's values in order, so a
        // space-separated string parsed back could not preserve what an identifier is
        // built from. This assembled the key from text; now it only assembles a name.
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
