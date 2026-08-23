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
            String keyText) {
        CanonicalSpec.DisplayNameMode safeMode = mode == null
                ? CanonicalSpec.DisplayNameMode.LABEL : mode;
        CanonicalSpec result = new CanonicalSpec().displayNameMode(safeMode);
        switch (safeMode) {
            case LABEL -> result.labelLanguage(language);
            case FIELD -> result.displayNameField(displayField);
            case TEMPLATE -> result.displayNameTemplate(template);
        }
        if (editsCanonicalKey(kind)) {
            for (String token : clean(keyText).split("[,;\\s]+")) {
                String key = token.trim();
                if (!key.isBlank() && !result.keyFields().contains(key)) {
                    result.keyFields().add(key);
                }
            }
        }
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
