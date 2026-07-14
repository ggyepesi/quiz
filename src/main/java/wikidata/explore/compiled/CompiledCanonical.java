package wikidata.explore.compiled;

import wikidata.explore.model.CanonicalSpec;
import java.util.List;

/** Immutable runtime form of canonical identity and display-name policy. */
public record CompiledCanonical(
        CanonicalSpec.Kind kind,
        List<String> keyFields,
        CanonicalSpec.DisplayNameMode displayNameMode,
        String displayNameField,
        String displayNameTemplate,
        String labelLanguage) {

    public CompiledCanonical {
        kind = kind == null ? CanonicalSpec.Kind.WIKIDATA_ENTITY : kind;
        keyFields = keyFields == null ? List.of() : List.copyOf(keyFields);
        displayNameMode = displayNameMode == null
                ? CanonicalSpec.DisplayNameMode.LABEL : displayNameMode;
        displayNameField = clean(displayNameField);
        displayNameTemplate = clean(displayNameTemplate);
        labelLanguage = clean(labelLanguage);
        if (labelLanguage.isBlank()) labelLanguage = "en";
    }

    public static CompiledCanonical from(CanonicalSpec spec) {
        CanonicalSpec s = spec == null ? new CanonicalSpec() : spec;
        return new CompiledCanonical(
                s.kind(), s.keyFields(), s.displayNameMode(),
                s.displayNameField(), s.displayNameTemplate(), s.labelLanguage());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
