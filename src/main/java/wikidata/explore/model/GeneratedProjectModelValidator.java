package wikidata.explore.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural validation for the editable generated-project model.
 *
 * <p>This validator does not issue network requests and does not verify that a
 * Wikidata PID or QID actually exists. It verifies the relationships internal
 * to the model so malformed StatementClass configuration fails before SPARQL
 * generation or reification.</p>
 */
public final class GeneratedProjectModelValidator {

    private GeneratedProjectModelValidator() {
    }

    public static ValidationResult validate(
            GeneratedProjectModel project) {

        List<Problem> problems = new ArrayList<>();

        if (project == null) {
            problems.add(Problem.error(
                    "",
                    "Project model is missing."));
            return new ValidationResult(problems);
        }

        validateUniqueClassNames(project, problems);

        for (GeneratedClassModel clazz : project.classes()) {
            if (clazz == null) {
                continue;
            }

            validateClassReferences(project, clazz, problems);
            validateCanonical(clazz, problems);

            if (clazz.reifiesStatements()) {
                validateStatementClass(
                        project,
                        clazz,
                        problems);
            }
        }

        return new ValidationResult(problems);
    }

    private static void validateUniqueClassNames(
            GeneratedProjectModel project,
            List<Problem> problems) {

        Set<String> names = new HashSet<>();
        for (GeneratedClassModel clazz : project.classes()) {
            if (clazz == null) {
                continue;
            }

            String normalized =
                    clean(clazz.className()).toLowerCase();
            if (!names.add(normalized)) {
                problems.add(Problem.error(
                        clazz.className(),
                        "Class name is duplicated."));
            }
        }
    }

    private static void validateClassReferences(
            GeneratedProjectModel project,
            GeneratedClassModel clazz,
            List<Problem> problems) {

        if (clazz.hasBase()
                && project.findClass(clazz.baseClassName()) == null) {
            problems.add(Problem.error(
                    clazz.className(),
                    "Base class '"
                            + clazz.baseClassName()
                            + "' does not exist."));
        }

        for (GeneratedFieldModel field : clazz.fields()) {
            if (field == null) {
                continue;
            }

            if (field.type() == FieldType.ENTITY
                    && !clean(field.entityClassName()).isBlank()
                    && project.findClass(field.entityClassName()) == null) {
                problems.add(Problem.error(
                        path(clazz, field),
                        "Referenced entity class '"
                                + field.entityClassName()
                                + "' does not exist."));
            }
        }
    }

    private static void validateStatementClass(
            GeneratedProjectModel project,
            GeneratedClassModel clazz,
            List<Problem> problems) {

        StatementClassSource source = clazz.statementSource();

        if (source == null
                || clean(source.sourceClassName()).isBlank()) {
            problems.add(Problem.error(
                    clazz.className(),
                    "Statement source class is required."));
            return;
        }

        if (!clean(source.propertyPid()).matches("(?i)P\\d+")) {
            problems.add(Problem.error(
                    clazz.className(),
                    "Statement property must be a valid PID."));
        }

        GeneratedClassModel sourceClass =
                project.findClass(source.sourceClassName());

        if (sourceClass == null) {
            problems.add(Problem.error(
                    clazz.className(),
                    "Statement source class '"
                            + source.sourceClassName()
                            + "' does not exist."));
        } else if (sourceClass == clazz) {
            problems.add(Problem.error(
                    clazz.className(),
                    "A statement class cannot reify its own statements."));
        }

        Set<String> fieldNames = fieldNames(clazz);

        for (GeneratedFieldModel field : clazz.fields()) {
            if (field == null) {
                continue;
            }

            FieldSourceMapping mapping = field.mapping();
            MissingQualifierPolicy policy =
                    mapping.missingQualifierPolicy();

            if (policy != null && !mapping.isQualifier()) {
                problems.add(Problem.error(
                        path(clazz, field),
                        "Missing-qualifier policy requires a qualifier PID."));
            }

            if (policy == MissingQualifierPolicy.STATEMENT_VALUE
                    && !hasStatementValueField(
                    clazz,
                    source == null
                            ? ""
                            : source.propertyPid())) {
                problems.add(Problem.error(
                        path(clazz, field),
                        "STATEMENT_VALUE fallback requires a statement-value field."));
            }

            validateFieldReference(
                    clazz,
                    field,
                    "subject field",
                    mapping.subjectField(),
                    fieldNames,
                    problems);

            validateFieldReference(
                    clazz,
                    field,
                    "match value field",
                    mapping.matchValueField(),
                    fieldNames,
                    problems);

            validateFieldReference(
                    clazz,
                    field,
                    "match role field",
                    mapping.matchRoleField(),
                    fieldNames,
                    problems);
        }
    }

    private static void validateCanonical(
            GeneratedClassModel clazz,
            List<Problem> problems) {

        CanonicalSpec canonical = clazz.effectiveCanonical();
        if (canonical == null) {
            return;
        }

        Set<String> fieldNames = fieldNames(clazz);

        if (canonical.isDerived()) {
            if (canonical.keyFields().isEmpty()) {
                problems.add(Problem.warning(
                        clazz.className(),
                        "Derived class has no canonical key; surrogate identity will be used."));
            }

            Set<String> seen = new LinkedHashSet<>();
            for (String keyField : canonical.keyFields()) {
                String name = clean(keyField);

                if (name.isBlank()) {
                    problems.add(Problem.error(
                            clazz.className(),
                            "Canonical key contains a blank field name."));
                    continue;
                }

                if (!fieldNames.contains(name)) {
                    problems.add(Problem.error(
                            clazz.className(),
                            "Canonical key field '"
                                    + name
                                    + "' does not exist."));
                    continue;
                }

                if (!seen.add(name)) {
                    problems.add(Problem.warning(
                            clazz.className(),
                            "Canonical key field '"
                                    + name
                                    + "' is repeated."));
                }

                GeneratedFieldModel field =
                        findField(clazz, name);
                if (field != null
                        && field.cardinality()
                        == FieldCardinality.COLLECTION) {
                    problems.add(Problem.error(
                            path(clazz, field),
                            "Collection field cannot participate in a canonical key."));
                }

                if (field != null
                        && field.mapping().productionKind()
                        != FieldProductionKind.AUTO) {
                    problems.add(Problem.error(
                            path(clazz, field),
                            "Derived/post-transform field cannot participate in a canonical key."));
                }
            }
        }

        if (canonical.displayNameMode()
                == CanonicalSpec.DisplayNameMode.FIELD) {
            String displayField =
                    clean(canonical.displayNameField());

            if (displayField.isBlank()
                    || !fieldNames.contains(displayField)) {
                problems.add(Problem.error(
                        clazz.className(),
                        "Display-name field '"
                                + displayField
                                + "' does not exist."));
            }
        }
    }

    private static boolean hasStatementValueField(
            GeneratedClassModel clazz,
            String propertyPid) {

        String pid = clean(propertyPid);
        for (GeneratedFieldModel field : clazz.fields()) {
            if (field == null
                    || field.isNameField()
                    || field.mapping().isQualifier()
                    || field.mapping().productionKind()
                    != FieldProductionKind.AUTO) {
                continue;
            }

            if (pid.equals(
                    clean(field.mapping().propertyPid()))) {
                return true;
            }
        }

        return false;
    }

    private static void validateFieldReference(
            GeneratedClassModel clazz,
            GeneratedFieldModel owner,
            String role,
            String referencedName,
            Set<String> fieldNames,
            List<Problem> problems) {

        String reference = clean(referencedName);

        // "source" is the synthetic subject of a reified statement, not a
        // configured model field.
        if (reference.isBlank()
                || "source".equals(reference)) {
            return;
        }

        if (!fieldNames.contains(reference)) {
            problems.add(Problem.error(
                    path(clazz, owner),
                    "Unknown "
                            + role
                            + " '"
                            + reference
                            + "'."));
        }
    }

    private static Set<String> fieldNames(
            GeneratedClassModel clazz) {

        Set<String> names = new LinkedHashSet<>();
        for (GeneratedFieldModel field : clazz.fields()) {
            if (field != null) {
                names.add(field.name());
            }
        }
        return names;
    }

    private static GeneratedFieldModel findField(
            GeneratedClassModel clazz,
            String name) {

        for (GeneratedFieldModel field : clazz.fields()) {
            if (field != null
                    && name.equals(field.name())) {
                return field;
            }
        }
        return null;
    }

    private static String path(
            GeneratedClassModel clazz,
            GeneratedFieldModel field) {

        return clazz.className() + "." + field.name();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public enum Severity {
        WARNING,
        ERROR
    }

    public record Problem(
            Severity severity,
            String location,
            String message) {

        public static Problem warning(
                String location,
                String message) {
            return new Problem(
                    Severity.WARNING,
                    clean(location),
                    message);
        }

        public static Problem error(
                String location,
                String message) {
            return new Problem(
                    Severity.ERROR,
                    clean(location),
                    message);
        }

        @Override
        public String toString() {
            String prefix =
                    location == null || location.isBlank()
                            ? ""
                            : location + ": ";
            return severity + ": " + prefix + message;
        }
    }

    public record ValidationResult(List<Problem> problems) {

        public ValidationResult {
            problems = problems == null
                    ? List.of()
                    : List.copyOf(problems);
        }

        public boolean valid() {
            return problems.stream()
                           .noneMatch(problem ->
                                              problem.severity()
                                                      == Severity.ERROR);
        }

        public List<Problem> errors() {
            return problems.stream()
                           .filter(problem ->
                                           problem.severity()
                                                   == Severity.ERROR)
                           .toList();
        }

        public List<Problem> warnings() {
            return problems.stream()
                           .filter(problem ->
                                           problem.severity()
                                                   == Severity.WARNING)
                           .toList();
        }

        public String format() {
            if (problems.isEmpty()) {
                return "Model is valid.";
            }

            return problems.stream()
                           .map(Problem::toString)
                           .collect(
                                   java.util.stream.Collectors
                                           .joining(System.lineSeparator()));
        }
    }
}
