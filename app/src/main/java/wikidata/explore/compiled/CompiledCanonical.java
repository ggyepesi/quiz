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
        List<String> keyFields,
        CanonicalSpec.DisplayNameMode displayNameMode,
        String displayNameField,
        String displayNameTemplate,
        String labelLanguage,
        String primaryListField) {

    public CompiledCanonical {
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
        primaryListField = clean(primaryListField);
    }

    /** Back-compat: no declared canonical list marker (the inference decides). */
    public CompiledCanonical(List<String> keyFields,
                             CanonicalSpec.DisplayNameMode displayNameMode,
                             String displayNameField, String displayNameTemplate,
                             String labelLanguage) {
        this(keyFields, displayNameMode, displayNameField,
                displayNameTemplate, labelLanguage, "");
    }



    /** Rebuilds an editable {@link CanonicalSpec} (for reuse with
     *  {@code Canonicalizer}, which is spec-typed). */
    public CanonicalSpec toSpec() {
        CanonicalSpec spec = new CanonicalSpec()
                .displayNameMode(displayNameMode)
                .displayNameField(displayNameField)
                .displayNameTemplate(displayNameTemplate)
                .labelLanguage(labelLanguage)
                .primaryListField(primaryListField);
        spec.keyFields().addAll(keyFields);
        return spec;
    }

    public static CompiledCanonical from(CanonicalSpec spec) {
        CanonicalSpec source = spec == null ? new CanonicalSpec() : spec;
        return new CompiledCanonical(
                source.keyFields(),
                source.displayNameMode(),
                source.displayNameField(),
                source.displayNameTemplate(),
                source.labelLanguage(),
                source.primaryListField());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
