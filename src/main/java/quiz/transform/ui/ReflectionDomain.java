package quiz.transform.ui;

import quiz.Quizable;
import quiz.QuizableAdapter;
import quiz.QuizableFieldPaths;
import quiz.ui.QuizableViews;
import quiz.ui.viewconfig.QuizablePanelConfig;

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
 * A {@link DomainModel} over hand-written {@code Quizable} domain objects (Nobel,
 * State, SportTeam, …) — the schema is derived by REFLECTION from the instance
 * classes ({@link QuizableAdapter#getAllFields}), a reference being a
 * {@code @QuizableReference} field or a {@code Quizable}-typed field/element, a
 * collection being a {@code Collection}/{@code Map}. The transform engine reads
 * these declared fields directly (FieldAccess falls back to reflection), so the
 * same view pipeline runs over them.
 */
public final class ReflectionDomain implements DomainModel {

    private final List<Quizable> instances;
    private final Map<String, List<DomainField>> fieldsByType = new LinkedHashMap<>();

    public ReflectionDomain(Collection<? extends Quizable> roots) {
        // Walk the whole reachable graph so EVERY class in the old data — the main
        // class AND its referenced ones (Person, Terms, Language, …) — becomes a
        // selectable type with its own instances, not just the top-level roots.
        java.util.Set<Object> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Deque<Quizable> queue = new java.util.ArrayDeque<>(roots);
        List<Quizable> closure = new ArrayList<>();
        Set<Class<?>> classes = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            Quizable q = queue.poll();
            if (q == null || !seen.add(q)) {
                continue;
            }
            closure.add(q);
            classes.add(q.getClass());
            queue.addAll(referencedQuizables(q));
        }
        this.instances = closure;
        for (Class<?> cls : classes) {
            index(cls);
        }
    }

    /** Quizables reachable through {@code q}'s fields — declared (reflection) and,
     *  for a dynamic object, its property map. */
    private static List<Quizable> referencedQuizables(Quizable q) {
        List<Quizable> out = new ArrayList<>();
        if (q instanceof quiz.DynamicFields dyn) {
            for (Object v : dyn.dynamicFieldValues().values()) {
                addQuizables(v, out);
            }
        }
        for (Field f : QuizableAdapter.getAllFields(q.getClass())) {
            try {
                f.setAccessible(true);
                addQuizables(f.get(q), out);
            } catch (Exception ignored) {
                // skip unreadable
            }
        }
        return out;
    }

    private static void addQuizables(Object v, List<Quizable> out) {
        if (v instanceof Quizable c) {
            out.add(c);
        } else if (v instanceof Collection<?> col) {
            for (Object i : col) {
                if (i instanceof Quizable c) out.add(c);
            }
        } else if (v instanceof Map<?, ?> m) {
            for (Object i : m.values()) {
                if (i instanceof Quizable c) out.add(c);
            }
        }
    }

    /** Build a domain from a {@link QuizableViews} builder (e.g. {@code new SportTeams()}). */
    public static ReflectionDomain of(QuizableViews views) throws Exception {
        views.buildViews();
        return new ReflectionDomain(views.getQuizables().values());
    }

    @SuppressWarnings("unchecked")
    private void index(Class<?> cls) {
        String type = cls.getSimpleName();
        if (fieldsByType.containsKey(type) || !Quizable.class.isAssignableFrom(cls)) {
            return;
        }
        List<DomainField> fields = new ArrayList<>();
        // QuizableFieldPaths gives the NESTED field paths (e.g. nominee.name) the
        // config editor renders — so nested/cross-class arguments appear for free.
        QuizablePanelConfig config =
                QuizablePanelConfig.all((Class<? extends Quizable>) cls);
        for (QuizableFieldPaths.FieldPath fp : QuizableFieldPaths.collect(config)) {
            Field leaf = fp.leafField();
            boolean ref = leaf != null && isReferenceField(leaf);
            boolean col = leaf != null && isCollectionField(leaf);
            FieldKind kind = col ? FieldKind.COLLECTION
                    : ref ? FieldKind.REFERENCE
                    : leaf != null ? FieldKind.ofClass(leaf.getType()) : FieldKind.UNKNOWN;
            fields.add(new DomainField(type, fp, ref, col, kind));
        }
        fieldsByType.put(type, fields);
    }

    static boolean isReferenceField(Field f) {
        if (QuizableAdapter.isQuizableReference(f)) {
            return true;
        }
        if (Quizable.class.isAssignableFrom(f.getType())) {
            return true;
        }
        // A collection/map of Quizables (via the generic element/value type).
        if (f.getGenericType() instanceof ParameterizedType p) {
            for (Type arg : p.getActualTypeArguments()) {
                if (arg instanceof Class<?> c && Quizable.class.isAssignableFrom(c)) {
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
    @Override public Collection<? extends Quizable> instances() { return instances; }
    @Override public Class<? extends Quizable> universe() { return Quizable.class; }
}
