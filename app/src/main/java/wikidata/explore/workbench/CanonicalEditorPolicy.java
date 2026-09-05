package wikidata.explore.workbench;

import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.ClassKind;

/** Pure policy behind the Identity & label controls; Swing only reflects it. */
final class CanonicalEditorPolicy {

    private CanonicalEditorPolicy() { }

    static boolean editsCanonicalKey(ClassKind kind) {
        return kind != null && kind.usesCanonicalKey();
    }

    /**
     * Where LABEL mode gets a name for this kind, or blank when it has none to get.
     *
     * <p>The question used to be {@code hasSourceLabel}, meaning "is this a SOURCE
     * class" — which reported that an owned class had no name in LABEL mode. It has one:
     * a part is produced on its owner's QID, so it cannot take a label of its own and is
     * given the owner and the site that produced it instead. That default is what keeps
     * it from reading as its owner, and it is a name, so LABEL resolves there.
     */
    static String labelSource(ClassKind kind) {
        if (kind == null) return "";
        return switch (kind) {
            case SOURCE -> "the datasource's label";
            case OWNED -> "its owner and the field that produced it";
            case STATEMENT, AGGREGATE -> "";
        };
    }

    static CanonicalSpec spec(
            ClassKind kind,
            CanonicalSpec.DisplayNameMode mode,
            String displayField,
            String template,
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
            // Which language a label is acquired in is not one of the three modes; it is
            // a parameter of the acquisition, authored beside the other source options
            // and written by the editor that owns that control. Taking it here meant
            // every panel sharing this had to invent one, and a blank invented value
            // resets a class configured in another language back to the default.
            case LABEL -> { }
            case FIELD -> result.displayNameField(displayField);
            case TEMPLATE -> result.displayNameTemplate(template);
        }
        // The key is not touched here at all. It belongs to ClassIdentityEditor, which
        // holds it as an ordered list — and identity joins a key's values in order, so a
        // space-separated string parsed back could not preserve what an identifier is
        // built from. This assembled the key from text; now it only assembles a name.
        return result;
    }
}
