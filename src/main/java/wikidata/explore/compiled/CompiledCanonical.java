package wikidata.explore.compiled;

import wikidata.explore.model.CanonicalSpec;

import java.util.List;

/**
 * Immutable runtime form of canonical identity and display-name policy.
 *
 * <p>All field names stored here have been resolved against the owning class's
 * effective fields by {@link ProjectModelCompiler}.</p>
 */
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
                ? CanonicalSpec.DisplayNameMode.LABEL
                : displayNameMode;
        displayNameField = clean(displayNameField);
        displayNameTemplate = clean(displayNameTemplate);
        labelLanguage = clean(labelLanguage);
        if (labelLanguage.isBlank()) {
            labelLanguage = "en";
        }
    }

    public boolean entityIdentity() {
        return kind == CanonicalSpec.Kind.WIKIDATA_ENTITY;
    }

    public boolean derivedIdentity() {
        return kind == CanonicalSpec.Kind.DERIVED;
    }

    public static CompiledCanonical from(CanonicalSpec spec) {
        CanonicalSpec source = spec == null ? new CanonicalSpec() : spec;
        return new CompiledCanonical(
                source.kind(),
                source.keyFields(),
                source.displayNameMode(),
                source.displayNameField(),
                source.displayNameTemplate(),
                source.labelLanguage());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
