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
        CanonicalSpec.DuplicatePolicy duplicatePolicy,
        CanonicalSpec.DisplayNameMode displayNameMode,
        String displayNameField,
        String displayNameTemplate,
        String labelLanguage,
        String primaryListField,
        java.util.Map<String, canonical.Reduction> reductions) {

    public CompiledCanonical {
        keyFields = keyFields == null ? List.of() : List.copyOf(keyFields);
        duplicatePolicy = duplicatePolicy == null
                ? CanonicalSpec.DuplicatePolicy.KEEP_ONE : duplicatePolicy;
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
        reductions = java.util.Map.copyOf(
                reductions == null ? java.util.Map.of() : reductions);
    }

    /** Back-compat: keep one duplicate and infer the canonical-list marker. */
    public CompiledCanonical(List<String> keyFields,
                             CanonicalSpec.DisplayNameMode displayNameMode,
                             String displayNameField, String displayNameTemplate,
                             String labelLanguage) {
        this(keyFields, CanonicalSpec.DuplicatePolicy.KEEP_ONE,
                displayNameMode, displayNameField,
                displayNameTemplate, labelLanguage, "", java.util.Map.of());
    }



    /** Rebuilds an editable {@link CanonicalSpec} (for reuse with
     *  {@code Canonicalizer}, which is spec-typed). */
    public CanonicalSpec toSpec() {
        CanonicalSpec spec = new CanonicalSpec()
                .duplicatePolicy(duplicatePolicy)
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
                source.duplicatePolicy(),
                source.displayNameMode(),
                source.displayNameField(),
                source.displayNameTemplate(),
                source.labelLanguage(),
                source.primaryListField(),
                source.reductions());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
