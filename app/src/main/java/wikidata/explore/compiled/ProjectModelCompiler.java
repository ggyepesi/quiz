package wikidata.explore.compiled;

import wikidata.explore.model.*;

import java.util.*;

/**
 * Semantic-analysis boundary between the mutable editor model and runtime.
 *
 * <p>The compiler snapshots and validates the project, then resolves
 * inheritance, canonical field references, sort fields and class references
 * into immutable compiled values.</p>
 */
public final class ProjectModelCompiler {

    private ProjectModelCompiler() {
    }

    public static CompiledProjectModel compile(
            GeneratedProjectModel editable) {

        if (editable == null) {
            throw new IllegalArgumentException(
                    "editable project must not be null");
        }

        // Never normalize the object currently owned by Swing controls.
        GeneratedProjectModel snapshot = editable.copy();
        GeneratedProjectModelValidator.ValidationResult validation =
                GeneratedProjectModelValidator.validate(snapshot);
        if (!validation.valid()) {
            throw new ModelCompilationException(validation);
        }

        List<CompiledClass> classes = new ArrayList<>();
        for (GeneratedClassModel clazz : snapshot.classes()) {
            classes.add(compileClass(snapshot, clazz));
        }

        return new CompiledProjectModel(
                snapshot.name(),
                snapshot.generationDepth(),
                snapshot.rootClass().className(),
                classes,
                snapshot.selections());
    }

    private static CompiledClass compileClass(
            GeneratedProjectModel project,
            GeneratedClassModel clazz) {

        GeneratedClassModel base =
                clazz.hasBase()
                        ? project.findClass(clazz.baseClassName())
                        : null;

        StatementClassSource statement = clazz.statementSource();
        GeneratedClassModel statementSource =
                statement == null
                        ? null
                        : project.findClass(statement.sourceClassName());

        List<CompiledField> ownFields =
                compileFields(project, clazz.fields(), newIdentitySet());
        List<CompiledField> effectiveFields =
                compileFields(
                        project,
                        clazz.effectiveFields(project),
                        newIdentitySet());

        CompiledCanonical canonical =
                compileCanonical(
                        clazz.canonical(),
                        effectiveFields,
                        clazz.className());

        return new CompiledClass(
                clazz.className(),
                clazz.displayClassName(),
                clazz.baseClassName(),
                base == null ? "" : base.className(),
                clazz.hasDiscriminator()
                        ? clazz.effectiveDiscriminatorPid()
                        : "",
                clazz.discriminatorQid(),
                clazz.generationDepth(),
                clazz.classKind(),
                CompiledFieldSource.from(
                        clazz.effectiveInstanceMapping(project)),
                clazz.seedQids(),
                canonical,
                CompiledStatementSource.from(
                        statement,
                        statementSource == null
                                ? ""
                                : statementSource.className(),
                        StatementFieldSemantics.statementValueFieldName(clazz)),
                ownFields,
                effectiveFields);
    }

    private static CompiledCanonical compileCanonical(
            CanonicalSpec source,
            List<CompiledField> effectiveFields,
            String className) {

        CanonicalSpec canonical =
                source == null ? new CanonicalSpec() : source;

        Map<String, CompiledField> fields =
                fieldIndex(effectiveFields);

        List<String> resolvedKeys = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Compilation resolves and de-duplicates the STORED field names; it does
        // not invent an identity key. Default selection is an earlier, explicit
        // model-editing operation (StatementCanonicalDefaults), and an empty list
        // must remain empty so editable and compiled execution stay equivalent.
        for (String configuredKey : canonical.keyFields()) {
            String configured = clean(configuredKey);
            CompiledField resolved =
                    fields.get(configured.toLowerCase(Locale.ROOT));

            if (resolved == null) {
                throw semanticError(
                        className,
                        "canonical key field '" + configured
                                + "' does not exist in effective fields");
            }

            String lower =
                    resolved.name().toLowerCase(Locale.ROOT);
            if (seen.add(lower)) {
                resolvedKeys.add(resolved.name());
            }
        }

        String resolvedDisplayField = "";
        if (canonical.displayNameMode()
                == CanonicalSpec.DisplayNameMode.FIELD) {

            String configured =
                    clean(canonical.displayNameField());
            CompiledField resolved =
                    fields.get(configured.toLowerCase(Locale.ROOT));

            if (resolved == null) {
                throw semanticError(
                        className,
                        "display-name field '" + configured
                                + "' does not exist in effective fields");
            }

            resolvedDisplayField = resolved.name();
        }

        return new CompiledCanonical(
                resolvedKeys,
                canonical.displayNameMode(),
                resolvedDisplayField,
                canonical.displayNameTemplate(),
                canonical.labelLanguage());
    }

    private static List<CompiledField> compileFields(
            GeneratedProjectModel project,
            List<GeneratedFieldModel> fields,
            Set<GeneratedFieldModel> activePath) {

        List<CompiledField> result = new ArrayList<>();

        for (GeneratedFieldModel field : fields) {
            if (field == null || field.isNameField()) {
                continue;
            }

            if (!activePath.add(field)) {
                throw semanticError(
                        field.name(),
                        "recursive nested-field containment detected");
            }

            try {
                GeneratedClassModel referenced =
                        field.entityClassName().isBlank()
                                ? null
                                : project.findClass(field.entityClassName());

                String resolvedSort =
                        resolveSortField(project, field, referenced);

                result.add(CompiledField.from(
                        field,
                        referenced == null
                                ? ""
                                : referenced.className(),
                        resolvedSort,
                        compileFields(
                                project,
                                field.fields(),
                                activePath)));
            } finally {
                activePath.remove(field);
            }
        }

        return result;
    }

    /**
     * A sort name belongs to the referenced entity class, not to the owning
     * class. Resolve it once here and preserve the configured name separately.
     */
    private static String resolveSortField(
            GeneratedProjectModel project,
            GeneratedFieldModel field,
            GeneratedClassModel referencedClass) {

        String configured = clean(field.sortFieldName());

        if (configured.isBlank()) {
            return "";
        }

        if (referencedClass == null) {
            throw semanticError(
                    field.name(),
                    "sort field '" + configured
                            + "' requires a modeled entity class");
        }

        for (GeneratedFieldModel candidate
                : referencedClass.effectiveFields(project)) {
            if (candidate != null
                    && configured.equalsIgnoreCase(
                            candidate.name())) {
                return candidate.name();
            }
        }

        throw semanticError(
                field.name(),
                "sort field '" + configured
                        + "' does not exist on class "
                        + referencedClass.className());
    }

    private static Map<String, CompiledField> fieldIndex(
            List<CompiledField> fields) {

        LinkedHashMap<String, CompiledField> index =
                new LinkedHashMap<>();
        for (CompiledField field : fields) {
            index.putIfAbsent(
                    field.name().toLowerCase(Locale.ROOT),
                    field);
        }
        return Collections.unmodifiableMap(index);
    }

    private static Set<GeneratedFieldModel> newIdentitySet() {
        return Collections.newSetFromMap(
                new IdentityHashMap<>());
    }

    private static ModelCompilationException semanticError(
            String location,
            String message) {
        return new ModelCompilationException(
                location == null || location.isBlank()
                        ? message
                        : location + ": " + message);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class ModelCompilationException
            extends IllegalArgumentException {

        private final GeneratedProjectModelValidator.ValidationResult
                validation;

        public ModelCompilationException(
                GeneratedProjectModelValidator.ValidationResult
                        validation) {

            super("Cannot compile invalid model:"
                    + System.lineSeparator()
                    + validation.format());
            this.validation = validation;
        }

        public ModelCompilationException(String message) {
            super("Cannot compile model: " + message);
            validation = null;
        }

        public GeneratedProjectModelValidator.ValidationResult
        validation() {
            return validation;
        }
    }
}
