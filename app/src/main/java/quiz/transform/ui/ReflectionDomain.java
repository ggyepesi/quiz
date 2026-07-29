package quiz.transform.ui;

import objectview.viewconfig.DomainViews;
import objectview.field.ViewableFieldPaths;
import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.field.FieldKind;
import objectview.viewconfig.ViewConfig;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
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
    private final Map<String, List<DomainField>> fieldsByType = new LinkedHashMap<>();
    private final Map<String, Set<String>> structuralByType = new LinkedHashMap<>();

    public ReflectionDomain(Collection<? extends Viewable> roots) {
        // Walk the whole reachable DATA graph so every Viewable class — the main
        // class AND referenced ones (Person, Terms, Language, …) — becomes selectable.
        // ViewableGroup implementations are structural. Some concrete implementations
        // inherit ViewableAdapter for reflection/rendering convenience, so exclude them
        // explicitly rather than promoting them to domain member types.
        java.util.Set<Object> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Deque<Viewable> queue = new java.util.ArrayDeque<>(roots);
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
            index(cls);
        }
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
        if (v instanceof objectview.group.ViewableGroup<?>) {
            return;
        }
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
        return new ReflectionDomain(roots);
    }

    @SuppressWarnings("unchecked")
    private void index(Class<?> cls) {
        String type = cls.getSimpleName();
        if (fieldsByType.containsKey(type) || !Viewable.class.isAssignableFrom(cls)) {
            return;
        }
        List<DomainField> fields = new ArrayList<>();
        // ViewableFieldPaths gives the NESTED field paths (e.g. nominee.name) the
        // config editor renders — so nested/cross-class arguments appear for free.
        // ALL_FIELDS: this feeds the transform pipeline picker and the coverage /
        // validation view, which WANT media (a MEDIA field is presence-filterable and
        // the missing-portrait / missing-flag worklist is the point). Search / sort /
        // config editors exclude media by kind separately (NOT_MEDIA_FIELDS).
        ViewConfig config =
                ViewConfig.all((Class<? extends Viewable>) cls);
        for (ViewableFieldPaths.FieldPath fp
                : ViewableFieldPaths.collect(config, ViewableFieldPaths.ALL_FIELDS)) {
            Field leaf = fp.leafField();
            boolean ref = leaf != null && isReferenceField(leaf);
            boolean col = leaf != null && isCollectionField(leaf);
            FieldKind kind = col ? FieldKind.COLLECTION
                    : ref ? FieldKind.REFERENCE
                    : leaf != null ? FieldKind.ofClass(leaf.getType()) : FieldKind.UNKNOWN;
            fields.add(new DomainField(type, fp, ref, col, kind));
        }
        fieldsByType.put(type, fields);

        Set<String> structural = new LinkedHashSet<>();
        for (Field field : ViewableAdapter.getAllFields(cls)) {
            if (isGroupField(field)) {
                structural.add(field.getName());
            }
        }
        structuralByType.put(type, Set.copyOf(structural));
    }

    private static boolean isGroupField(Field field) {
        if (field == null) {
            return false;
        }
        if (objectview.group.ViewableGroup.class.isAssignableFrom(field.getType())) {
            return true;
        }
        if (field.getGenericType() instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (argument instanceof Class<?> cls
                        && objectview.group.ViewableGroup.class.isAssignableFrom(cls)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isReferenceField(Field f) {
        if (ViewableAdapter.isReference(f)) {
            return true;
        }
        if (Viewable.class.isAssignableFrom(f.getType())) {
            return true;
        }
        // A collection/map of Viewables (via the generic element/value type).
        if (f.getGenericType() instanceof ParameterizedType p) {
            for (Type arg : p.getActualTypeArguments()) {
                if (arg instanceof Class<?> c && Viewable.class.isAssignableFrom(c)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isCollectionField(Field f) {
        Class<?> t = f.getType();
        return Collection.class.isAssignableFrom(t) || Map.class.isAssignableFrom(t);
    }

    @Override public List<String> types() { return new ArrayList<>(fieldsByType.keySet()); }
    @Override public List<DomainField> fields(String type) {
        return new ArrayList<>(fieldsByType.getOrDefault(type, List.of()));
    }
    @Override public Set<String> structuralFields(String type) {
        return structuralByType.getOrDefault(type, Set.of());
    }
    @Override public Collection<? extends Viewable> instances() { return instances; }
    @Override public Class<? extends Viewable> universe() { return Viewable.class; }
}
