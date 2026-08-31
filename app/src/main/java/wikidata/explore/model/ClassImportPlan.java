package wikidata.explore.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An authored class configuration and the model declarations it depends on.
 * Snapshot objects, observed vocabulary members, counts and curation are not part
 * of a {@link GeneratedProjectModel}, so they cannot accidentally cross domains.
 */
public final class ClassImportPlan {

    public enum ConflictPolicy { REPLACE, REUSE_TARGET }

    private final GeneratedProjectModel source;
    private final GeneratedProjectModel target;
    private final String requestedClass;
    private final List<GeneratedClassModel> classes;
    private final List<Selection> selections;
    private final List<EntityKindRule> kindRules;
    private final Set<String> conflicts;

    private ClassImportPlan(
            GeneratedProjectModel source,
            GeneratedProjectModel target,
            String requestedClass,
            List<GeneratedClassModel> classes,
            List<Selection> selections,
            List<EntityKindRule> kindRules,
            Set<String> conflicts) {
        this.source = source;
        this.target = target;
        this.requestedClass = requestedClass;
        this.classes = List.copyOf(classes);
        this.selections = List.copyOf(selections);
        this.kindRules = List.copyOf(kindRules);
        this.conflicts = Set.copyOf(conflicts);
    }

    public static ClassImportPlan of(
            GeneratedProjectModel source,
            GeneratedProjectModel target,
            String className) {
        if (source == null || target == null) {
            throw new IllegalArgumentException("Source and target models are required");
        }
        GeneratedClassModel requested = source.findClass(className);
        if (requested == null) {
            throw new IllegalArgumentException("Source class not found: " + className);
        }

        LinkedHashMap<String, GeneratedClassModel> closure = new LinkedHashMap<>();
        ArrayDeque<GeneratedClassModel> pending = new ArrayDeque<>();
        pending.add(requested);
        while (!pending.isEmpty()) {
            GeneratedClassModel clazz = pending.removeFirst();
            if (closure.putIfAbsent(clazz.className(), clazz) != null) continue;
            addClassDependency(source, pending, clazz.baseClassName());
            if (clazz.statementSource() != null) {
                addClassDependency(source, pending,
                        clazz.statementSource().sourceClassName());
            }
            collectFieldClassDependencies(source, pending, clazz.fields());
        }

        LinkedHashSet<String> selectionNames = new LinkedHashSet<>();
        for (GeneratedClassModel clazz : closure.values()) {
            if (clazz.statementSource() != null
                    && clazz.statementSource().hasValueSelection()) {
                selectionNames.add(clazz.statementSource().valueSelectionName());
            }
            collectFieldSelections(source, selectionNames, clazz.fields());
        }
        // Role selections are declarations over an imported class/field and belong
        // with that class even when no field points back to the selection by name.
        for (Selection selection : source.selections()) {
            if (selection instanceof RoleSelection role
                    && closure.containsKey(role.ownerClassName())) {
                selectionNames.add(role.name());
            }
        }

        List<Selection> selections = source.selections().stream()
                .filter(s -> s != null && selectionNames.contains(s.name()))
                .toList();
        List<EntityKindRule> rules = source.entityKindRules().stream()
                .filter(r -> r != null && closure.containsKey(r.className()))
                .toList();
        LinkedHashSet<String> conflicts = new LinkedHashSet<>();
        closure.keySet().stream().filter(n -> target.findClass(n) != null)
                .forEach(conflicts::add);
        selections.stream().map(Selection::name)
                .filter(n -> target.findSelection(n) != null).forEach(conflicts::add);

        return new ClassImportPlan(source, target, requested.className(),
                new ArrayList<>(closure.values()), selections, rules, conflicts);
    }

    public String requestedClass() { return requestedClass; }
    public List<GeneratedClassModel> classes() { return classes; }
    public List<Selection> selections() { return selections; }
    public List<EntityKindRule> kindRules() { return kindRules; }
    public Set<String> conflicts() { return conflicts; }

    public Set<String> dependencyClassNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        classes.stream().map(GeneratedClassModel::className)
                .filter(n -> !requestedClass.equals(n)).forEach(names::add);
        return names;
    }

    /** Apply selected classes; declarations required by those classes follow them. */
    /**
     * Whether the classes land as this project's own or as the source model's.
     *
     * <p>These are different acts, not settings of one. COPY eases configuring a class
     * that resembles another: the result is the copier's, freely editable, with no
     * lasting relationship. IMPORT uses a model's class where it stands: the class is
     * shown and referenced here and edited only in the model that owns it.
     */
    public enum Ownership { COPY, IMPORT }

    public List<GeneratedClassModel> apply(
            Set<String> selectedClassNames,
            ConflictPolicy policy) {
        return apply(selectedClassNames, policy, Ownership.COPY);
    }

    public List<GeneratedClassModel> apply(
            Set<String> selectedClassNames,
            ConflictPolicy policy,
            Ownership ownership) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (selectedClassNames != null) selected.addAll(selectedClassNames);
        selected.add(requestedClass);
        validateSelectedClosure(selected);

        GeneratedProjectModel candidate = target.copy();
        Set<String> existingErrors = GeneratedProjectModelValidator.validate(target)
                .errors().stream().map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());

        List<GeneratedClassModel> imported = new ArrayList<>();
        for (GeneratedClassModel sourceClass : classes) {
            if (!selected.contains(sourceClass.className())) continue;
            GeneratedClassModel existing = candidate.findClass(sourceClass.className());
            if (existing != null && policy == ConflictPolicy.REUSE_TARGET) continue;
            GeneratedClassModel copy = sourceClass.copy();
            // An import is owned by the model it names, and that ownership survives
            // being passed along: importing a class the source had itself imported
            // still points at the model that owns it, not at the relay. A copy claims
            // nothing, so it arrives owned by nobody but this project.
            if (ownership == Ownership.COPY) {
                copy.importedFrom("");
            } else if (!copy.isImported()) {
                copy.importedFrom(source.name());
            }
            candidate.replaceClass(copy);
            imported.add(copy);
        }

        Set<String> requiredSelections = requiredSelectionNames(selected);
        for (Selection selection : selections) {
            if (!requiredSelections.contains(selection.name())) continue;
            if (candidate.findSelection(selection.name()) != null
                    && policy == ConflictPolicy.REUSE_TARGET) continue;
            candidate.replaceSelection(selection.copy());
        }
        for (EntityKindRule rule : kindRules) {
            if (!selected.contains(rule.className())) continue;
            if (policy == ConflictPolicy.REUSE_TARGET
                    && candidate.findClass(rule.className()) != null
                    && imported.stream().noneMatch(c -> c.className().equals(rule.className()))) {
                continue;
            }
            candidate.replaceEntityKindRule(rule.copy());
        }

        List<GeneratedProjectModelValidator.Problem> introduced =
                GeneratedProjectModelValidator.validate(candidate).errors().stream()
                        .filter(problem -> !existingErrors.contains(problem.toString()))
                        .toList();
        if (!introduced.isEmpty()) {
            throw new IllegalStateException("The copied fragment would make the model invalid:\n"
                    + introduced.stream().map(Object::toString)
                            .collect(java.util.stream.Collectors.joining("\n")));
        }
        target.copyContentsFrom(candidate);
        return List.copyOf(imported);
    }

    private void validateSelectedClosure(Set<String> selected) {
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        for (GeneratedClassModel clazz : classes) {
            if (!selected.contains(clazz.className())) continue;
            for (String dependency : directClassDependencies(source, clazz)) {
                if (!selected.contains(dependency) && target.findClass(dependency) == null) {
                    missing.add(clazz.className() + " → " + dependency);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Select the missing class dependencies:\n"
                    + String.join("\n", missing));
        }
    }

    private Set<String> requiredSelectionNames(Set<String> selected) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (GeneratedClassModel clazz : classes) {
            if (!selected.contains(clazz.className())) continue;
            if (clazz.statementSource() != null
                    && clazz.statementSource().hasValueSelection()) {
                names.add(clazz.statementSource().valueSelectionName());
            }
            collectFieldSelections(source, names, clazz.fields());
        }
        for (Selection selection : selections) {
            if (selection instanceof RoleSelection role
                    && selected.contains(role.ownerClassName())) names.add(role.name());
        }
        return names;
    }

    private static Set<String> directClassDependencies(
            GeneratedProjectModel source, GeneratedClassModel clazz) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (source.findClass(clazz.baseClassName()) != null) names.add(clazz.baseClassName());
        if (clazz.statementSource() != null
                && source.findClass(clazz.statementSource().sourceClassName()) != null) {
            names.add(clazz.statementSource().sourceClassName());
        }
        collectFieldClassNames(source, names, clazz.fields());
        return names;
    }

    private static void addClassDependency(
            GeneratedProjectModel source, ArrayDeque<GeneratedClassModel> pending,
            String name) {
        GeneratedClassModel dependency = source.findClass(name);
        if (dependency != null) pending.add(dependency);
    }

    private static void collectFieldClassDependencies(
            GeneratedProjectModel source, ArrayDeque<GeneratedClassModel> pending,
            List<GeneratedFieldModel> fields) {
        if (fields == null) return;
        for (GeneratedFieldModel field : fields) {
            if (field == null) continue;
            addClassDependency(source, pending, field.entityClassName());
            collectFieldClassDependencies(source, pending, field.fields());
        }
    }

    private static void collectFieldClassNames(
            GeneratedProjectModel source, Set<String> names,
            List<GeneratedFieldModel> fields) {
        if (fields == null) return;
        for (GeneratedFieldModel field : fields) {
            if (field == null) continue;
            if (source.findClass(field.entityClassName()) != null) {
                names.add(field.entityClassName());
            }
            collectFieldClassNames(source, names, field.fields());
        }
    }

    private static void collectFieldSelections(
            GeneratedProjectModel source, Set<String> names,
            List<GeneratedFieldModel> fields) {
        if (fields == null) return;
        for (GeneratedFieldModel field : fields) {
            if (field == null) continue;
            if (source.findSelection(field.entityClassName()) != null) {
                names.add(field.entityClassName());
            }
            collectFieldSelections(source, names, field.fields());
        }
    }
}
