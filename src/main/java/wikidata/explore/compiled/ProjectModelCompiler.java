package wikidata.explore.compiled;

import wikidata.explore.model.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Semantic-analysis boundary between the mutable editor model and runtime.
 *
 * <p>The compiler snapshots, migrates and validates the project, then resolves
 * inheritance and class references into immutable compiled values.</p>
 */
public final class ProjectModelCompiler {
    private ProjectModelCompiler() { }

    public static CompiledProjectModel compile(GeneratedProjectModel editable) {
        if (editable == null) {
            throw new IllegalArgumentException("editable project must not be null");
        }

        // Never normalize the object currently owned by Swing controls.
        GeneratedProjectModel snapshot = editable.copy();
        GeneratedProjectModelMigration.migrate(snapshot);

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
                snapshot.name(), snapshot.generationDepth(),
                snapshot.rootClass().className(), classes);
    }

    private static CompiledClass compileClass(
            GeneratedProjectModel project, GeneratedClassModel clazz) {
        GeneratedClassModel base = clazz.hasBase()
                ? project.findClass(clazz.baseClassName()) : null;
        StatementClassSource statement = clazz.statementSource();
        GeneratedClassModel statementSource = statement == null
                ? null : project.findClass(statement.sourceClassName());

        return new CompiledClass(
                clazz.className(), clazz.displayClassName(),
                clazz.baseClassName(), base == null ? "" : base.className(),
                clazz.hasDiscriminator() ? clazz.effectiveDiscriminatorPid() : "",
                clazz.discriminatorQid(), clazz.generationDepth(),
                CompiledFieldSource.from(clazz.effectiveInstanceMapping(project)),
                clazz.seedQids(),
                compileFacets(clazz.facets()),
                CompiledCanonical.from(clazz.effectiveCanonical()),
                CompiledStatementSource.from(statement,
                        statementSource == null ? "" : statementSource.className()),
                compileFields(project, clazz.fields()),
                compileFields(project, clazz.effectiveFields(project)));
    }

    private static List<CompiledFacet> compileFacets(List<GeneratedFacet> facets) {
        List<CompiledFacet> result = new ArrayList<>();
        for (GeneratedFacet facet : facets) {
            if (facet != null) {
                result.add(CompiledFacet.from(facet));
            }
        }
        return result;
    }

    private static List<CompiledField> compileFields(
            GeneratedProjectModel project, List<GeneratedFieldModel> fields) {
        List<CompiledField> result = new ArrayList<>();
        for (GeneratedFieldModel field : fields) {
            if (field == null || field.isNameField()) continue;
            GeneratedClassModel referenced = field.entityClassName().isBlank()
                    ? null : project.findClass(field.entityClassName());
            result.add(CompiledField.from(
                    field,
                    referenced == null ? "" : referenced.className(),
                    compileFields(project, field.fields())));
        }
        return result;
    }

    public static final class ModelCompilationException
            extends IllegalArgumentException {
        private final GeneratedProjectModelValidator.ValidationResult validation;

        public ModelCompilationException(
                GeneratedProjectModelValidator.ValidationResult validation) {
            super("Cannot compile invalid model:" + System.lineSeparator()
                    + validation.format());
            this.validation = validation;
        }

        public GeneratedProjectModelValidator.ValidationResult validation() {
            return validation;
        }
    }
}
