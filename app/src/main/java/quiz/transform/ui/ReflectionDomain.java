package quiz.transform.ui;

import objectview.viewconfig.DomainViews;
import objectview.viewconfig.FieldTypeSource;
import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.field.FieldRef;
import objectview.field.FieldSchema;
import objectview.field.ReflectionFieldSet;
import objectview.field.ViewableFieldPaths;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link DomainModel} over hand-written {@code Viewable} domain objects (Nobel,
 * State, SportTeam, …) — the schema is derived by REFLECTION from the instance
 * classes ({@link ViewableAdapter#getAllFields}), a reference being a
 * {@code @Reference} field or a {@code Viewable}-typed field/element, a
 * collection being a {@code Collection}/{@code Map}. The transform engine reads
 * these declared fields directly (FieldAccess falls back to reflection), so the
 * same view pipeline runs over them.
 */
public final class ReflectionDomain implements DomainModel {

    private final List<Viewable> instances;
    private final List<? extends Viewable> memberRoots;
    private final List<objectview.viewconfig.DomainGroupRoot> groupRootBindings;
    private final List<String> memberTypes = new ArrayList<>();
    private final Map<String, FieldSchema> schemasByType =
            new LinkedHashMap<>();
    private final Map<String, String> baseTypes = new LinkedHashMap<>();

    public ReflectionDomain(Collection<? extends Viewable> roots) {
        this(roots, List.<objectview.group.ViewableGroup<?>>of());
    }

    public ReflectionDomain(
            Collection<? extends Viewable> roots,
            Collection<? extends objectview.group.ViewableGroup<?>> groupRoots) {
        this(roots, bindLegacyRoots(roots, groupRoots));
    }

    private ReflectionDomain(
            Collection<? extends Viewable> roots,
            List<objectview.viewconfig.DomainGroupRoot> groupRootBindings) {
        this.memberRoots = roots == null ? List.of() : List.copyOf(roots);
        this.groupRootBindings = groupRootBindings == null
                ? List.of() : List.copyOf(groupRootBindings);
        // Walk the whole reachable object graph so every Viewable class — the main
        // class AND referenced ones (Person, Terms, Language, ViewableGroup, …) —
        // uses the same discovery, schema and persistence rules.
        java.util.Set<Object> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Deque<Viewable> queue = new java.util.ArrayDeque<>(memberRoots);
        this.groupRootBindings.forEach(binding -> queue.add(binding.root()));
        List<Viewable> closure = new ArrayList<>();
        Set<Class<?>> classes = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            Viewable q = queue.poll();
            if (q == null || !seen.add(q)) {
                continue;
            }
            closure.add(q);
            classes.add(q.getClass());
            queue.addAll(referencedViewables(q));
        }
        this.instances = closure;
        for (Class<?> cls : classes) {
            addMemberClassHierarchy(cls);
            index(cls);
        }
    }

    /** A concrete instance discovers both its direct Java class and every Viewable
     * superclass. This keeps a domain containing only USState instances usable as a
     * State domain without requiring a separate State instance as a discovery seed. */
    private void addMemberClassHierarchy(Class<?> cls) {
        for (Class<?> current = cls;
             current != null && Viewable.class.isAssignableFrom(current);) {
            if (!memberTypes.contains(current.getSimpleName())) {
                memberTypes.add(current.getSimpleName());
            }
            Class<?> superclass = current.getSuperclass();
            if (isFrameworkBase(superclass)) break;
            current = superclass;
        }
    }

    private static boolean isFrameworkBase(Class<?> type) {
        return type == null || type == ViewableAdapter.class
                || type == objectview.group.DefaultViewableGroup.class;
    }

    /** Viewables reachable through {@code q}'s fields, read through the ONE FieldSet
     *  bridge (#87) — a dynamic object's property map or a typed object's declared
     *  fields, with no `instanceof DynamicFields` fork. */
    private static List<Viewable> referencedViewables(Viewable q) {
        List<Viewable> out = new ArrayList<>();
        objectview.field.FieldSet fs = objectview.field.FieldSet.of(q);
        for (objectview.field.FieldRef fr : fs.fields()) {
            addViewables(fs.read(fr.name()), out);
        }
        return out;
    }

    private static void addViewables(Object v, List<Viewable> out) {
        if (v instanceof Viewable c) {
            out.add(c);
        } else if (v instanceof Collection<?> col) {
            for (Object i : col) {
                addViewables(i, out);
            }
        } else if (v instanceof Map<?, ?> m) {
            for (Object i : m.values()) {
                addViewables(i, out);
            }
        }
    }

    /** Build a domain from a {@link DomainViews} builder (e.g. {@code new SportTeams()}). */
    public static ReflectionDomain of(DomainViews views) throws Exception {
        views.buildViews();
        // getViewables() is typed Viewable (objectview SPI); the elements are Viewables.
        @SuppressWarnings("unchecked")
        Collection<? extends Viewable> roots =
                (Collection<? extends Viewable>) (Collection<?>) views.getViewables().values();
        return new ReflectionDomain(roots, views.getGroupRootBindings());
    }

    private static List<objectview.viewconfig.DomainGroupRoot> bindLegacyRoots(
            Collection<? extends Viewable> members,
            Collection<? extends objectview.group.ViewableGroup<?>> roots) {
        String memberType = members == null ? null : members.stream()
                .filter(java.util.Objects::nonNull)
                .map(Viewable::typeName)
                .findFirst().orElse(null);
        if (memberType == null || roots == null) return List.of();
        return roots.stream().filter(java.util.Objects::nonNull)
                .map(root -> new objectview.viewconfig.DomainGroupRoot(memberType, root))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private void index(Class<?> cls) {
        String type = cls.getSimpleName();
        if (schemasByType.containsKey(type) || !Viewable.class.isAssignableFrom(cls)) {
            return;
        }
        Class<?> superclass = cls.getSuperclass();
        if (superclass != null
                && !isFrameworkBase(superclass)
                && Viewable.class.isAssignableFrom(superclass)) {
            baseTypes.put(type, superclass.getSimpleName());
            index(superclass);
        }
        List<FieldRef> fields = new ArrayList<>();
        List<Class<? extends Viewable>> nestedClasses = new ArrayList<>();
        for (Field field : ViewableAdapter.getAllFields(cls)) {
            FieldRef described = ReflectionFieldSet.describe(field, cls);
            if (described.provenance()) {
                described = FieldRef.withStructural(described, true);
            }
            fields.add(described);
            Class<? extends Viewable> nested =
                    ViewableFieldPaths.nestedViewableClass(field);
            if (nested != null && !described.structural()) {
                nestedClasses.add(nested);
            }
        }
        List<FieldRef> immutable = List.copyOf(fields);
        schemasByType.put(type, () -> immutable);
        for (Class<? extends Viewable> nested : nestedClasses) {
            index(nested);
        }
    }

    @Override public List<String> types() { return List.copyOf(memberTypes); }
    @Override public String baseType(String type) { return baseTypes.get(type); }
    @Override public List<DomainField> fields(String type) {
        return DomainSchemas.fields(this, type);
    }
    @Override public FieldSchema fieldSchema(String type) {
        return schemasByType.get(type);
    }
    @Override public Set<String> structuralFields(String type) {
        return DomainSchemas.structuralFields(fieldSchema(type));
    }
    @Override public FieldTypeSource fieldTypes(String type) {
        return DomainSchemas.fieldTypes(this, type);
    }
    @Override public Collection<? extends Viewable> instances() { return instances; }
    @Override public Collection<? extends Viewable> memberRoots() { return memberRoots; }
    @Override public List<? extends objectview.group.ViewableGroup<?>> groupRoots() {
        return DomainModel.super.groupRoots();
    }
    @Override public List<objectview.viewconfig.DomainGroupRoot> groupRootBindings() {
        return groupRootBindings;
    }
    @Override public Class<? extends Viewable> universe() { return Viewable.class; }
}
