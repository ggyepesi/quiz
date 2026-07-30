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
    private final List<String> memberTypes = new ArrayList<>();
    private final Map<String, FieldSchema> schemasByType =
            new LinkedHashMap<>();

    public ReflectionDomain(Collection<? extends Viewable> roots) {
        // Walk the whole reachable object graph so every Viewable class — the main
        // class AND referenced ones (Person, Terms, Language, ViewableGroup, …) —
        // uses the same discovery, schema and persistence rules.
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
            memberTypes.add(cls.getSimpleName());
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
        if (schemasByType.containsKey(type) || !Viewable.class.isAssignableFrom(cls)) {
            return;
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
    @Override public Class<? extends Viewable> universe() { return Viewable.class; }
}
