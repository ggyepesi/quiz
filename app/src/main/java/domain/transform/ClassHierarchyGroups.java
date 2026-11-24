package domain.transform;

import domain.DomainModel;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import objectview.Viewable;
import objectview.group.StrictMemberHierarchy;
import objectview.viewconfig.DomainGroupRoot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * The single projection of a declared class hierarchy into ObjectView groups.
 *
 * <p>The hierarchy remains model data. These read-only groups are rebuilt over the
 * current instance pool for every consumer; they are never another saved group tree.</p>
 */
public final class ClassHierarchyGroups {

    private ClassHierarchyGroups() { }

    public static List<DomainGroupRoot> rootBindings(
            GeneratedProjectModel model,
            Collection<? extends Viewable> instances) {
        if (model == null) return List.of();
        List<String> types = model.classes().stream()
                .filter(java.util.Objects::nonNull)
                .map(GeneratedClassModel::className)
                .toList();
        return rootBindings(types,
                type -> {
                    GeneratedClassModel clazz = model.findClass(type);
                    return clazz == null || !clazz.hasBase()
                            ? null : clazz.baseClassName();
                },
                (value, type) -> isInstanceOf(model, value, type),
                instances);
    }

    public static List<DomainGroupRoot> rootBindings(DomainModel domain) {
        if (domain == null) return List.of();
        return rootBindings(domain.types(), domain::baseType, domain::isInstanceOf,
                domain.instances());
    }

    /** Adds any hierarchy nodes not already present under an authored/configured root. */
    public static void addChildren(
            EditableGroup root, String baseType, DomainModel domain) {
        if (root == null || baseType == null || domain == null) return;
        List<String> types = declaredTypes(domain.types());
        List<Viewable> pool = instancePool(domain.instances());
        Map<String, String> parents = effectiveParents(
                types, domain::baseType, domain::isInstanceOf, pool, baseType);
        removeClassChildren(root);
        addChildren(root, baseType, types, parents,
                domain::isInstanceOf, new HashSet<>());
        root.reproduceDescendants(domain);
    }

    private static List<DomainGroupRoot> rootBindings(
            Collection<String> types,
            Function<String, String> baseType,
            BiPredicate<Viewable, String> membership,
            Collection<? extends Viewable> instances) {
        List<String> declared = declaredTypes(types);
        List<Viewable> pool = instancePool(instances);
        Map<String, String> parents = effectiveParents(
                declared, baseType, membership, pool, null);
        List<DomainGroupRoot> result = new ArrayList<>();
        for (String type : declared) {
            if (directSubtypes(type, declared, parents).isEmpty()) continue;
            EditableGroup root = new EditableGroup("All " + type);
            root.replaceMembers(pool.stream()
                    .filter(value -> membership.test(value, type)).toList());
            addChildren(root, type, declared, parents, membership, new HashSet<>());
            root.reproduceDescendants();
            result.add(new DomainGroupRoot(type, root));
        }
        return List.copyOf(result);
    }

    private static void addChildren(
            EditableGroup parent,
            String baseType,
            Collection<String> types,
            Map<String, String> parentByType,
            BiPredicate<Viewable, String> membership,
            Set<String> visited) {
        if (!visited.add(baseType)) return;
        for (String subtype : directSubtypes(baseType, types, parentByType)) {
            EditableGroup child = new ClassSubtypeGroup(subtype, membership);
            parent.addGroup(child);
            addChildren(child, subtype, types, parentByType, membership, visited);
        }
    }

    private static List<String> directSubtypes(
            String baseType,
            Collection<String> types,
            Map<String, String> parentByType) {
        return types.stream()
                .filter(candidate -> baseType.equals(parentByType.get(candidate)))
                .sorted().toList();
    }

    /**
     * Refines declared single inheritance with observed semantic containment.
     *
     * <p>Only siblings in the declared branch are considered. A non-empty class moves
     * below the smallest strict containing class when every one of its current instances
     * satisfies that class's membership predicate. Equal and empty populations provide
     * no evidence, and the strict size ordering makes cycles impossible.</p>
     */
    /**
     * @param scopeRoot when set, only that class's declared branch is refined: a type's
     *        group root shows nothing outside it, so membership is computed for the
     *        branch alone instead of every class over the whole pool for every root.
     */
    private static Map<String, String> effectiveParents(
            List<String> types,
            Function<String, String> declaredBase,
            BiPredicate<Viewable, String> membership,
            List<Viewable> pool,
            String scopeRoot) {
        Map<String, String> declared = new LinkedHashMap<>();
        for (String type : types) {
            String base = declaredBase.apply(type);
            if (base != null && types.contains(base)) declared.put(type, base);
        }
        // Only classes in a declared hierarchy can be refined; a class with neither a base
        // nor a subclass costs nothing, and a branch with one class has nothing to order.
        List<String> participants = types.stream()
                .filter(type -> scopeRoot == null
                        ? declared.containsKey(type) || declared.containsValue(type)
                        : isSameOrDescendant(type, scopeRoot, declared))
                .toList();
        if (participants.size() < 2) return Map.copyOf(declared);
        Map<String, List<Viewable>> members = new LinkedHashMap<>();
        for (String type : participants) {
            members.put(type, pool.stream()
                    .filter(value -> membership.test(value, type)).toList());
        }

        Map<String, String> result = new LinkedHashMap<>(declared);
        result.putAll(StrictMemberHierarchy.parents(participants,
                type -> members.getOrDefault(type, List.of()),
                declared::containsKey,
                (candidate, child) -> {
                    String base = declared.get(child);
                    return base != null
                            && isSameOrDescendant(candidate, base, declared)
                            && !isSameOrDescendant(candidate, child, declared);
                }));
        return Map.copyOf(result);
    }

    private static boolean isSameOrDescendant(
            String candidate, String expected, Map<String, String> declaredParents) {
        Set<String> seen = new HashSet<>();
        for (String current = candidate; current != null && seen.add(current);
                current = declaredParents.get(current)) {
            if (expected.equals(current)) return true;
        }
        return false;
    }

    private static List<String> declaredTypes(Collection<String> types) {
        return types == null ? List.of() : types.stream()
                .filter(java.util.Objects::nonNull)
                .filter(type -> !type.isBlank()).distinct().toList();
    }

    private static List<Viewable> instancePool(
            Collection<? extends Viewable> instances) {
        return instances == null ? List.of() : instances.stream()
                .filter(java.util.Objects::nonNull).map(Viewable.class::cast).toList();
    }

    private static void removeClassChildren(EditableGroup parent) {
        List<ClassSubtypeGroup> children = parent.getChildren().stream()
                .filter(ClassSubtypeGroup.class::isInstance)
                .map(ClassSubtypeGroup.class::cast).toList();
        children.forEach(parent::removeGroup);
    }

    private static boolean isInstanceOf(
            GeneratedProjectModel model, Viewable value, String expected) {
        if (value == null) return false;
        for (String direct : value.directClassNames()) {
            if (model.isSameOrSubclass(direct, expected)) return true;
        }
        return expected.equals(value.typeName());
    }
}
