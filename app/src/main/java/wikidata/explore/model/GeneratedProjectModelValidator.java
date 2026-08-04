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
            validateBaseCycle(project, clazz, problems);
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
                    && project.findClass(field.entityClassName()) == null
                    && project.findSelection(field.entityClassName()) == null) {
                // An unmodeled ref target is a valid state, not an error: the field
                // renders as a display-name string (no class promotion, no QID chip)
                // — e.g. Nomination.forWork -> ForWork, which has no class. A target
                // naming a VOCABULARY Selection (e.g. category -> OscarCategories) is
                // fully resolved, not dangling, so it's excluded above. Warn so a
                // genuine typo is still visible, but don't block the save.
                problems.add(Problem.warning(
                        path(clazz, field),
                        "Referenced entity class '"
                                + field.entityClassName()
                                + "' is not modeled; the field renders as a string."));
            }
        }
    }

    /**
     * Flags an inheritance cycle (A extends B extends A). Without this the
     * flattening in {@code effectiveFields} silently stops at the visited-set
     * guard, so the compiled class would quietly lose the looped fields. Reported
     * once per cycle, attributed to its alphabetically-first member.
     */
    private static void validateBaseCycle(
            GeneratedProjectModel project,
            GeneratedClassModel clazz,
            List<Problem> problems) {

        if (!clazz.hasBase()) {
            return;
        }

        List<String> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        GeneratedClassModel current = clazz;

        while (current != null && current.hasBase()) {
            String key = clean(current.className()).toLowerCase();
            if (!seen.add(key)) {
                int start = 0;
                for (int i = 0; i < path.size(); i++) {
                    if (clean(path.get(i)).toLowerCase().equals(key)) {
                        start = i;
                        break;
                    }
                }
                List<String> cycle = path.subList(start, path.size());
                String owner = cycle.stream()
                        .min(java.util.Comparator.comparing(String::toLowerCase))
                        .orElse(clazz.className());
                if (clean(clazz.className()).equalsIgnoreCase(owner)) {
                    List<String> shown = new ArrayList<>(cycle);
                    shown.add(cycle.get(0));
                    problems.add(Problem.error(
                            clazz.className(),
                            "Base class cycle: " + String.join(" -> ", shown)));
                }
                return;
            }
            path.add(current.className());
            current = project.findClass(current.baseClassName());
        }
    }

    // A source-class-less reify must bound the subjects it discovers: a P31
    // value-type filter, or a referenced VOCABULARY Selection supplying the values.
    private static boolean hasBoundedValueDomain(
            GeneratedClassModel clazz, StatementClassSource source) {
        return clean(clazz.instanceMapping().sourceQid()).matches("(?i)Q\\d+")
                || source.hasValueSelection();
    }

    private static void validateStatementClass(
            GeneratedProjectModel project,
            GeneratedClassModel clazz,
            List<Problem> problems) {

        StatementClassSource source = clazz.statementSource();

        if (source == null
                || !clean(source.propertyPid()).matches("(?i)P\\d+")) {
            problems.add(Problem.error(
                    clazz.className(),
                    "Statement property must be a valid PID."));
            return;
        }

        // The source class is OPTIONAL: blank means the reify DISCOVERS its subjects
        // (the entities carrying the property into the value domain). That needs a
        // bounded value domain — a value-type filter, an explicit value set, or a
        // referenced VOCABULARY — else the membership scan is unbounded.
        if (clean(source.sourceClassName()).isBlank()) {
            if (!hasBoundedValueDomain(clazz, source)) {
                problems.add(Problem.error(
                        clazz.className(),
                        "A statement class with no source class discovers its "
                                + "subjects and needs a bounded value domain "
                                + "(value type, value set, or a VOCABULARY)."));
            }
            return;
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

        // The value role is explicit: a non-qualifier runtime field must map to the
        // statement property. Its absence means the reified records get an empty
        // value (the old code guessed a field instead) — surface it as a warning so
        // the misconfiguration is visible without blocking save/compile.
        boolean hasNonQualifierRuntimeField = clazz.fields().stream()
                .anyMatch(f -> StatementFieldSemantics.isRuntimeStatementField(f)
                        && !f.mapping().isQualifier());
        if (hasNonQualifierRuntimeField
                && StatementFieldSemantics.statementValueFieldName(clazz).isEmpty()) {
            problems.add(Problem.warning(
                    clazz.className(),
                    "No value field: a non-qualifier field should map to the statement "
                            + "property " + clean(source.propertyPid())
                            + ", else reified records have an empty value."));
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

        CanonicalSpec canonical = clazz.canonical();

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
                        && !StatementFieldSemantics
                        .isCanonicalKeyCandidate(field)) {
                    problems.add(Problem.error(
                            path(clazz, field),
                            "Only scalar, Auto-produced fields can "
                                    + "participate in a canonical key."));
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
        // Centralized: the value role is the non-qualifier runtime field on the
        // statement PID (propertyPid is what the predicate derives from the class).
        return !StatementFieldSemantics.statementValueFieldName(clazz).isEmpty();
    }

    private static void validateFieldReference(
            GeneratedClassModel clazz,
            GeneratedFieldModel owner,
            String role,
            String referencedName,
            Set<String> fieldNames,
            List<Problem> problems) {

        String reference = clean(referencedName);

        // "source" is the synthetic subject of a reified statement; a dotted path
        // (e.g. date.year) is a typed projection resolved against another class at
        // compile time, not a flat field of THIS class. Neither is a local field
        // name to check here — path validity is settled downstream, not structurally.
        if (reference.isBlank()
                || "source".equals(reference)
                || reference.contains(".")) {
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
