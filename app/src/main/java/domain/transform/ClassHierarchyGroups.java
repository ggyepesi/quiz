package domain.transform;

import domain.DomainModel;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import objectview.Viewable;
import objectview.viewconfig.DomainGroupRoot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
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
        addChildren(root, baseType, domain.types(), domain::baseType,
                domain::isInstanceOf, new HashSet<>());
        root.reproduceDescendants(domain);
    }

    private static List<DomainGroupRoot> rootBindings(
            Collection<String> types,
            Function<String, String> baseType,
            BiPredicate<Viewable, String> membership,
            Collection<? extends Viewable> instances) {
        List<String> declared = types == null ? List.of()
                : types.stream().filter(java.util.Objects::nonNull).distinct().toList();
        List<Viewable> pool = instances == null ? List.of()
                : instances.stream().filter(java.util.Objects::nonNull)
                        .map(Viewable.class::cast).toList();
        List<DomainGroupRoot> result = new ArrayList<>();
        for (String type : declared) {
            if (directSubtypes(type, declared, baseType).isEmpty()) continue;
            EditableGroup root = new EditableGroup("All " + type);
            root.replaceMembers(pool.stream()
                    .filter(value -> membership.test(value, type)).toList());
            addChildren(root, type, declared, baseType, membership, new HashSet<>());
            root.reproduceDescendants();
            result.add(new DomainGroupRoot(type, root));
        }
        return List.copyOf(result);
    }

    private static void addChildren(
            EditableGroup parent,
            String baseType,
            Collection<String> types,
            Function<String, String> baseTypeOf,
            BiPredicate<Viewable, String> membership,
            Set<String> visited) {
        if (!visited.add(baseType)) return;
        for (String subtype : directSubtypes(baseType, types, baseTypeOf)) {
            boolean alreadyPresent = parent.getChildren().stream()
                    .anyMatch(child -> ("@class:" + subtype).equals(child.groupKey()));
            EditableGroup child = alreadyPresent ? null
                    : new ClassSubtypeGroup(subtype, membership);
            if (child != null) parent.addGroup(child);
            objectview.group.ViewableGroup<?> actual = child != null ? child
                    : parent.getChildren().stream()
                            .filter(candidate -> ("@class:" + subtype)
                                    .equals(candidate.groupKey()))
                            .findFirst().orElse(null);
            if (actual instanceof EditableGroup editable) {
                addChildren(editable, subtype, types, baseTypeOf, membership, visited);
            }
        }
    }

    private static List<String> directSubtypes(
            String baseType,
            Collection<String> types,
            Function<String, String> baseTypeOf) {
        return types.stream()
                .filter(candidate -> baseType.equals(baseTypeOf.apply(candidate)))
                .sorted().toList();
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
