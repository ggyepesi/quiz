package wikidata.explore.model;

import datasource.schema.FieldType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        validateImports(project, problems);
        validateModulePresentationOverlays(project, problems);
        validateUniqueDeclarationIds(project, problems);
        validateSelectionsAndKindRules(project, problems);
        validateOwnedComponentCycles(project, problems);
        validateAggregateCycles(project, problems);

        for (GeneratedClassModel clazz : project.classes()) {
            if (clazz == null) {
                continue;
            }

            validateClassReferences(project, clazz, problems);
            validateOwnedClass(project, clazz, problems);
            validateOwnedComponentFields(project, clazz, problems);
            validateInverseFields(project, clazz, problems);
            validateBaseCycle(project, clazz, problems);
            validateCanonical(clazz, problems);
            validateStatementSubjectFields(clazz, problems);
            validateValueLanguages(clazz, problems);
            validateAggregateClass(project, clazz, problems);

            if (clazz.reifiesStatements()) {
                validateStatementClass(
                        project,
                        clazz,
                        problems);
            }
        }

        return new ValidationResult(problems);
    }

    private static void validateModulePresentationOverlays(
            GeneratedProjectModel project, List<Problem> problems) {
        Set<String> targets = new HashSet<>();
        for (ModelClassPresentationOverlay overlay
                : project.modulePresentationOverlays()) {
            if (overlay == null || overlay.classDeclarationId().isBlank()) {
                problems.add(Problem.error(project.name(),
                        "Imported-class presentation overlay requires a class identity."));
                continue;
            }
            if (!targets.add(overlay.classDeclarationId())) {
                problems.add(Problem.error(project.name(),
                        "Imported class has more than one presentation overlay: "
                                + overlay.classDeclarationId()));
            }
        }
    }

    private static void validateImports(
            GeneratedProjectModel project, List<Problem> problems) {
        Set<String> coordinates = new HashSet<>();
        Map<String, String> versions = new HashMap<>();
        for (ModelModuleImport dependency : project.imports()) {
            if (dependency == null || !dependency.complete()) {
                problems.add(Problem.error(project.name(),
                        "Model-module imports require id, version, digest and declarations."));
                continue;
            }
            if (!coordinates.add(dependency.coordinate())) {
                problems.add(Problem.error(project.name(),
                        "Model module is imported more than once: "
                                + dependency.coordinate()));
            }
            String previous = versions.putIfAbsent(
                    dependency.moduleId(), dependency.version());
            if (previous != null && !previous.equals(dependency.version())) {
                problems.add(Problem.error(project.name(),
                        "Model module has incompatible imported versions: "
                                + dependency.moduleId() + "@" + previous + " and "
                                + dependency.coordinate()));
            }
        }
    }

    private static void validateUniqueDeclarationIds(
            GeneratedProjectModel project, List<Problem> problems) {
        Set<String> ids = new HashSet<>();
        for (GeneratedClassModel clazz : project.classes()) {
            if (clazz == null || clazz.declarationId().isBlank()) continue;
            if (!ids.add(clazz.declarationId())) {
                problems.add(Problem.error(clazz.className(),
                        "Declaration identity is also used by another class or selection: "
                                + clazz.declarationId()));
            }
        }
        for (Selection selection : project.selections()) {
            if (selection == null || selection.declarationId().isBlank()) continue;
            if (!ids.add(selection.declarationId())) {
                problems.add(Problem.error(selection.name(),
                        "Declaration identity is also used by another class or selection: "
                                + selection.declarationId()));
            }
        }
    }

    private static void validateAggregateCycles(GeneratedProjectModel project,
            List<Problem> problems) {
        for (GeneratedClassModel start : project.classes()) {
            if (start == null || start.aggregateSource() == null) continue;
            Set<String> seen = new LinkedHashSet<>();
            GeneratedClassModel current = start;
            while (current != null && current.aggregateSource() != null) {
                if (!seen.add(current.className())) {
                    problems.add(Problem.error(start.className(),
                            "Aggregate source cycle: " + String.join(" → ", seen)
                                    + " → " + current.className() + "."));
                    break;
                }
                current = project.findClass(current.aggregateSource().sourceClassName());
            }
        }
    }

    private static void validateAggregateClass(GeneratedProjectModel project,
            GeneratedClassModel aggregate, List<Problem> problems) {
        AggregateClassSource spec = aggregate.aggregateSource();
        if (aggregate.classKind() != ClassKind.AGGREGATE && spec == null) return;
        if (spec == null || !spec.configured()) {
            problems.add(Problem.error(aggregate.className(),
                    "Aggregate class requires a source class, grouping key and members field."));
            return;
        }
        GeneratedClassModel source = project.findClass(spec.sourceClassName());
        if (source == null || source == aggregate) {
            problems.add(Problem.error(aggregate.className(),
                    "Aggregate source class '" + spec.sourceClassName() + "' does not exist."));
            return;
        }
        GeneratedFieldModel members = findField(aggregate, spec.membersField());
        if (members == null || members.type() != FieldType.ENTITY
                || members.cardinality() != FieldCardinality.COLLECTION
                || !source.className().equals(members.entityClassName())) {
            problems.add(Problem.error(aggregate.className(),
                    "Aggregate members field must be a list-valued ENTITY field of class "
                            + source.className() + "."));
        }
        Set<String> targets = new LinkedHashSet<>();
        for (AggregateClassSource.Key key : spec.keys()) {
            GeneratedFieldModel target = findField(aggregate, key.targetField());
            GeneratedFieldModel input = findField(source, key.sourceField());
            if (target == null) problems.add(Problem.error(aggregate.className(),
                    "Aggregate key target field '" + key.targetField() + "' does not exist."));
            if (input == null) problems.add(Problem.error(aggregate.className(),
                    "Aggregate key source field '" + key.sourceField() + "' does not exist on "
                            + source.className() + "."));
            if (!targets.add(key.targetField())) problems.add(Problem.error(aggregate.className(),
                    "Aggregate key target field '" + key.targetField() + "' is repeated."));
            if (target != null && target.cardinality() == FieldCardinality.COLLECTION
                    || input != null && input.cardinality() == FieldCardinality.COLLECTION) {
                problems.add(Problem.error(aggregate.className(),
                        "Aggregate grouping fields must be scalar: " + key.targetField() + "."));
            }
        }
    }

    /**
     * The shared Value language control has two representations: entity-valued
     * language projections use a Wikidata QID, while monolingual text uses a language
     * code. Validate that distinction before a generation run starts.
     */
    private static void validateValueLanguages(
            GeneratedClassModel owner, List<Problem> problems) {
        for (GeneratedFieldModel field : owner.fields()) {
            if (field == null) continue;
            String configured = clean(field.mapping().valueLanguage());
            if (configured.isBlank()) continue;
            try {
                if (field.type() == FieldType.ENTITY) {
                    wikidata.WikidataLanguageDefaults.entityQid(configured);
                } else if (field.type() == FieldType.STRING
                        || field.type() == FieldType.TEXT) {
                    wikidata.WikidataLanguageDefaults.literalCode(configured);
                } else {
                    // Inert, not wrong. A field that changed type keeps the language it
                    // was given, and that leftover produces no bad data — it selects
                    // nothing on a kind of value that states no language. Blocking a
                    // run for it would fail models that were correct before this check
                    // existed; saying so lets the reader clear it when they pass by.
                    problems.add(Problem.warning(path(owner, field),
                            "Value language is ignored on a " + field.type()
                                    + " field; it applies to ENTITY, STRING and TEXT."));
                }
            } catch (IllegalArgumentException invalid) {
                problems.add(Problem.error(path(owner, field), invalid.getMessage()));
            }
        }
    }

    private static void validateStatementSubjectFields(
            GeneratedClassModel owner, List<Problem> problems) {
        int subjects = 0;
        for (GeneratedFieldModel field : owner.fields()) {
            if (field == null) {
                continue;
            }
            if (field.mapping().productionKind()
                    == FieldProductionKind.STATEMENT_PARTICIPANTS) {
                if (!owner.reifiesStatements()) {
                    problems.add(Problem.error(path(owner, field),
                            "Statement participants are available only on a Statement class."));
                }
                if (field.type() != FieldType.ENTITY
                        || field.cardinality() != FieldCardinality.COLLECTION) {
                    problems.add(Problem.error(path(owner, field),
                            "Statement participants must be a list-valued ENTITY field."));
                }
                if (!wikidata.WikidataIds.isPid(
                        clean(field.mapping().qualifierPid()))) {
                    problems.add(Problem.error(path(owner, field),
                            "Statement participants require an entity qualifier PID."));
                }
                continue;
            }
            if (field.mapping().productionKind()
                    != FieldProductionKind.STATEMENT_SUBJECT) continue;
            subjects++;
            if (!owner.reifiesStatements()) {
                problems.add(Problem.error(path(owner, field),
                        "Statement subject is available only on a Statement class."));
            }
            if (field.type() != FieldType.ENTITY
                    || field.cardinality() == FieldCardinality.COLLECTION) {
                problems.add(Problem.error(path(owner, field),
                        "Statement subject must be a single ENTITY field."));
            }
            if (!clean(field.mapping().propertyPid()).isBlank()
                    || !clean(field.mapping().qualifierPid()).isBlank()) {
                problems.add(Problem.error(path(owner, field),
                        "Statement subject must not declare a property or qualifier."));
            }
        }
        if (subjects > 1) {
            problems.add(Problem.error(owner.className(),
                    "A Statement class must declare at most one Statement subject field; "
                            + subjects + " are configured."));
        }
    }

    private static void validateInverseFields(
            GeneratedProjectModel project, GeneratedClassModel owner,
            List<Problem> problems) {
        for (GeneratedFieldModel inverse : owner.fields()) {
            if (inverse == null || inverse.mapping().productionKind()
                    != FieldProductionKind.INVERT) continue;
            if (inverse.type() != FieldType.ENTITY
                    || inverse.cardinality() != FieldCardinality.COLLECTION) {
                problems.add(Problem.error(path(owner, inverse),
                        "An inverse must be a collection-valued ENTITY field."));
                continue;
            }
            GeneratedClassModel forwardOwner =
                    project.findClass(inverse.entityClassName());
            if (forwardOwner == null) continue; // ordinary reference validation owns it
            List<GeneratedFieldModel> candidates = forwardOwner.fields().stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(field -> field.type() == FieldType.ENTITY)
                    .filter(field -> owner.className().equals(field.entityClassName()))
                    .toList();
            String selected = clean(inverse.mapping().inverseField());
            // Ask the question generation asks, not a stricter one: a property match
            // resolves two class references to a single answer, and reporting that as
            // ambiguous would fail a model that inverts correctly.
            String pid = clean(inverse.mapping().propertyPid());
            List<String> referencingOwner =
                    candidates.stream().map(GeneratedFieldModel::name).toList();
            List<String> alsoMatchingPid = pid.isBlank() ? List.of()
                    : candidates.stream()
                            .filter(field -> pid.equals(clean(field.mapping().propertyPid())))
                            .map(GeneratedFieldModel::name).toList();
            if (InverseFieldResolution.resolve(
                    selected, referencingOwner, alsoMatchingPid) != null) {
                continue;
            }
            problems.add(Problem.error(path(owner, inverse),
                    !selected.isBlank()
                            ? "Inverse field '" + forwardOwner.className() + "."
                              + selected + "' does not reference " + owner.className() + "."
                            : referencingOwner.isEmpty()
                            ? "No field on " + forwardOwner.className()
                              + " references " + owner.className() + "."
                            : "Several fields on " + forwardOwner.className()
                              + " reference " + owner.className()
                              + "; choose the exact inverse field."));
        }
    }

    private static void validateOwnedClass(
            GeneratedProjectModel project, GeneratedClassModel clazz,
            List<Problem> problems) {
        if (!clazz.ownedClass()) return;
        if (clazz.hasBase()) {
            GeneratedClassModel base = project.findClass(clazz.baseClassName());
            if (base != null && !base.ownedClass()) {
                problems.add(Problem.error(clazz.className(),
                        "An Owned class can extend only another Owned class; '"
                                + base.className() + "' is " + base.classKind() + "."));
            }
        }
        FieldSourceMapping own = clazz.instanceMapping();
        if (clazz.reifiesStatements() || !clazz.seedQids().isEmpty()
                || !clean(own.sourceQid()).isBlank()
                || !clean(own.propertyPid()).isBlank()
                || !own.additionalTypeQids().isEmpty()) {
            problems.add(Problem.error(clazz.className(),
                    "An Owned class cannot also define an independent membership source."));
        }
        // A class with no entity of its own has no label to take. Its instances render
        // as their fields; a TEMPLATE over those fields is the way to give one a name.
        if (clazz.canonical() != null && clazz.canonical().displayNameMode()
                == CanonicalSpec.DisplayNameMode.LABEL) {
            problems.add(Problem.warning(clazz.className(),
                    "An Owned class has no Wikidata entity of its own, so a LABEL "
                            + "display name would be its OWNER's. Its instances render "
                            + "as their fields; use a TEMPLATE to name them."));
        }
        // An Owned class may be produced at several sites, but its FIELDS are shared by
        // all of them and load from the owner's entity — so every site must be on the
        // same kind of owner. Person.fullname + Person.birthName is fine; adding
        // Organisation.legalName would make Name.familyName (P734, a property of humans)
        // meaningless for half its instances.
        List<GeneratedClassModel> owners =
                MembershipPattern.owningEntityClasses(clazz, project);
        if (owners.size() > 1) {
            problems.add(Problem.error(clazz.className(),
                    "Owned class '" + clazz.className() + "' is produced from different "
                            + "kinds of entity ("
                            + owners.stream().map(GeneratedClassModel::className)
                                    .collect(java.util.stream.Collectors.joining(", "))
                            + ") at " + MembershipPattern.ownedBy(clazz, project).stream()
                                    .map(site -> site.ownerClass() + "." + site.fieldName())
                                    .collect(java.util.stream.Collectors.joining(", "))
                            + ". Its fields load from the owner, so one owned class "
                            + "cannot serve owners of different kinds — give each its own."));
        }
    }

    private static void validateOwnedComponentFields(
            GeneratedProjectModel project, GeneratedClassModel owner,
            List<Problem> problems) {
        for (GeneratedFieldModel field : owner.fields()) {
            if (field == null || field.mapping().productionKind()
                    != FieldProductionKind.OWNED_COMPONENT) continue;
            if (field.type() != FieldType.ENTITY
                    || field.cardinality() != FieldCardinality.SINGLE) {
                problems.add(Problem.error(path(owner, field),
                        "An owned component must be a single ENTITY field."));
            }
            if (!clean(field.mapping().propertyPid()).isBlank()) {
                problems.add(Problem.error(path(owner, field),
                        "An owned component field has no property; its target fields "
                                + "load properties using the owner identity."));
            }
            GeneratedClassModel target = project.findClass(field.entityClassName());
            if (target == null) continue; // ordinary reference validation reports it
            if (!target.ownedClass()) {
                problems.add(Problem.error(path(owner, field),
                        "QID-from-owner production requires target class '"
                                + target.className() + "' to be configured as Owned."));
            }
            // Equality is the self-cycle diagnosed by validateOwnedComponentCycles.
            // This check is specifically for a distinct owner that already inherits
            // the target and would redundantly compose it a second time.
            if (!owner.className().equals(target.className())
                    && project.isSameOrSubclass(owner.className(), target.className())) {
                problems.add(Problem.error(path(owner, field),
                        "The owner already is a " + target.className()
                                + " through class extension; a nested component is redundant."));
            }
            FieldSourceMapping effective = target.effectiveInstanceMapping(project);
            boolean independentlyPopulated = target.reifiesStatements()
                    || !target.seedQids().isEmpty()
                    || effective != null && (!clean(effective.sourceQid()).isBlank()
                        || !effective.additionalTypeQids().isEmpty())
                    || MembershipPattern.kindRule(target, project) != null;
            if (independentlyPopulated) {
                problems.add(Problem.error(path(owner, field),
                        "Owned component class '" + target.className()
                                + "' must not define an independent membership source."));
            }
        }
    }

    private static void validateOwnedComponentCycles(
            GeneratedProjectModel project, List<Problem> problems) {
        for (GeneratedClassModel start : project.classes()) {
            if (start == null) continue;
            java.util.LinkedHashSet<String> path = new java.util.LinkedHashSet<>();
            if (ownedCycle(project, start.className(), start.className(), path)) {
                problems.add(Problem.error(start.className(),
                        "Owned-component cycle: " + String.join(" -> ", path)
                                + " -> " + start.className()));
            }
        }
    }

    private static boolean ownedCycle(
            GeneratedProjectModel project, String start, String current,
            java.util.LinkedHashSet<String> path) {
        if (!path.add(current)) return current.equals(start);
        GeneratedClassModel model = project.findClass(current);
        if (model != null) for (GeneratedFieldModel field : model.fields()) {
            if (field != null && field.mapping().productionKind()
                    == FieldProductionKind.OWNED_COMPONENT) {
                String next = clean(field.entityClassName());
                if (next.equals(start)
                        || (!next.isBlank() && ownedCycle(project, start, next, path))) {
                    return true;
                }
            }
        }
        path.remove(current);
        return false;
    }

    private static void validateSelectionsAndKindRules(
            GeneratedProjectModel project, List<Problem> problems) {
        for (Selection selection : project.selections()) {
            if (!(selection instanceof RoleSelection role)) continue;
            GeneratedClassModel owner = project.findClass(role.ownerClassName());
            if (owner == null) {
                problems.add(Problem.error(role.name(), "Role owner class '"
                        + role.ownerClassName() + "' does not exist."));
                continue;
            }
            GeneratedFieldModel field = owner.fields().stream()
                    .filter(value -> value != null
                            && role.fieldName().equals(value.name()))
                    .findFirst().orElse(null);
            if (field == null) {
                problems.add(Problem.error(role.name(), "Role field '"
                        + role.ownerClassName() + "." + role.fieldName()
                        + "' does not exist."));
            } else if (field.type() != FieldType.ENTITY) {
                problems.add(Problem.error(role.name(), "Role field must be entity-valued."));
            }
        }
        for (EntityKindRule rule : project.entityKindRules()) {
            if (rule == null || !rule.isConfigured()) {
                problems.add(Problem.error("Entity kinds", "Kind rule is incomplete."));
            } else if (project.findClass(rule.className()) == null) {
                problems.add(Problem.error("Entity kinds", "Kind class '"
                        + rule.className() + "' does not exist."));
            }
        }
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
                    && !field.unclassedEntity()
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
            if (field.graphExpansionPolicy()
                    != datasource.graph.GraphExpansionPolicy.NONE) {
                if (!WikidataFieldGraphTraversalEligibility
                        .hasTypedModeledTarget(project, field)) {
                    problems.add(Problem.error(path(clazz, field),
                            "Graph expansion requires a typed entity field targeting "
                                    + "a modeled class."));
                }
                if (!WikidataFieldGraphTraversalEligibility.hasPropertySource(field)) {
                    problems.add(Problem.error(path(clazz, field),
                            "Wikidata graph expansion requires a Pxx property source."));
                }
            }
            WikipediaCategoryRule category = field.wikipediaCategoryRule();
            if (category != null) {
                long placeholders = category.pattern().split("<value>", -1).length - 1L;
                if (placeholders != 1) {
                    problems.add(Problem.error(path(clazz, field),
                            "A Wikipedia category pattern must contain exactly one "
                                    + "<value> placeholder."));
                }
                if (category.policy() != CategoryCandidatePolicy.EVIDENCE_ONLY
                        && field.type() != FieldType.ENTITY) {
                    problems.add(Problem.error(path(clazz, field),
                            "A category relation that produces values requires an "
                                    + "entity-valued field."));
                }
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
            GeneratedProjectModel project,
            GeneratedClassModel clazz,
            StatementClassSource source) {
        if (clean(clazz.instanceMapping().sourceQid()).matches("(?i)Q\\d+")
                || source.hasValueSelection()) {
            return true;
        }
        String valueField = StatementFieldSemantics.statementValueFieldName(clazz);
        GeneratedFieldModel field = clazz.fields().stream()
                .filter(f -> valueField.equals(f.name()))
                .findFirst().orElse(null);
        if (field == null) {
            return false;
        }
        if (field.mapping().allowedQids().stream()
                .map(GeneratedProjectModelValidator::clean)
                .anyMatch(q -> q.matches("(?i)Q\\d+"))) {
            return true;
        }
        GeneratedClassModel target = project.findClass(field.entityClassName());
        return target != null && target.seedQids().stream()
                .map(GeneratedProjectModelValidator::clean)
                .anyMatch(q -> q.matches("(?i)Q\\d+"));
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
            if (!hasBoundedValueDomain(project, clazz, source)) {
                problems.add(Problem.error(
                        clazz.className(),
                        "A statement class with no source class discovers its "
                                + "subjects and needs a bounded value domain "
                                + "(value type, value set, seeded value class, "
                                + "or a VOCABULARY)."));
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

        // Whether an absent qualifier stays absent or resolves to the statement subject
        // decides both what the record contains and whether its denormalized copy
        // collapses into it. A field that states nothing takes whatever the default is
        // at the time, so the same model can mean two different things in two releases
        // — which is precisely how the Oscars nominee/forWork fallbacks were lost.
        // A warning, not an error: it must be visible without blocking a save.
        for (GeneratedFieldModel field : clazz.fields()) {
            if (StatementFieldSemantics.supportsMissingQualifierPolicy(clazz, field)
                    && field.mapping().missingQualifierPolicy() == null) {
                problems.add(Problem.warning(
                        path(clazz, field),
                        "No missing-qualifier policy: this field follows the current "
                                + "default (leave missing). State it explicitly — a "
                                + "reference like an edition should stay missing, while "
                                + "a role the subject itself plays needs the statement "
                                + "subject."));
            }
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

        if (clazz.classKind().usesCanonicalKey()) {
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
                if (field != null && !StatementFieldSemantics.isCanonicalKeyCandidate(field)) {
                    problems.add(Problem.error(
                            path(clazz, field),
                            "Only scalar runtime fields and normalized Statement "
                                    + "participants can participate in a canonical key."));
                }
            }
        }

        // #92: a declared canonical-list marker that cannot mark anything would fall
        // back to the inference it was written to override — silently, and only for
        // this class. Say so at validation instead.
        String primaryList = clean(canonical.primaryListField());
        if (!primaryList.isBlank()) {
            GeneratedFieldModel field = findField(clazz, primaryList);
            if (field == null) {
                problems.add(Problem.error(
                        clazz.className(),
                        "Canonical list field '" + primaryList + "' does not exist."));
            } else if (!StatementFieldSemantics.isRuntimeStatementField(field)
                    || !field.mapping().isQualifier()
                    || field.type() != FieldType.ENTITY
                    || field.cardinality() == null
                    || !field.cardinality().isCollection()) {
                problems.add(Problem.error(
                        path(clazz, field),
                        "Only a multi-valued entity qualifier can mark the canonical "
                                + "copy of a statement."));
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
