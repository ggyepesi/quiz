package quiz.transform.ui;

import quiz.Quizable;
import quiz.QuizableAdapter;
import quiz.ui.QuizableViews;

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
    private final Map<String, List<String>> fieldsByType = new LinkedHashMap<>();
    private final Set<String> referenceFields = new LinkedHashSet<>();
    private final Set<String> collectionFields = new LinkedHashSet<>();

    public ReflectionDomain(Collection<? extends Quizable> instances) {
        this.instances = new ArrayList<>(instances);
        Set<Class<?>> classes = new LinkedHashSet<>();
        for (Quizable q : this.instances) {
            if (q != null) {
                classes.add(q.getClass());
            }
        }
        for (Class<?> cls : classes) {
            index(cls);
        }
    }

    /** Build a domain from a {@link QuizableViews} builder (e.g. {@code new SportTeams()}). */
    public static ReflectionDomain of(QuizableViews views) throws Exception {
        views.buildViews();
        return new ReflectionDomain(views.getQuizables().values());
    }

    private void index(Class<?> cls) {
        String type = cls.getSimpleName();
        if (fieldsByType.containsKey(type)) {
            return;
        }
        List<String> fields = new ArrayList<>();
        for (Field f : QuizableAdapter.getAllFields(cls)) {
            fields.add(f.getName());
            String key = type + "." + f.getName();
            if (isReferenceField(f)) {
                referenceFields.add(key);
            }
            if (isCollectionField(f)) {
                collectionFields.add(key);
            }
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
    @Override public List<String> fields(String type) {
        return new ArrayList<>(fieldsByType.getOrDefault(type, List.of()));
    }
    @Override public boolean isReference(String t, String f) { return referenceFields.contains(t + "." + f); }
    @Override public boolean isCollection(String t, String f) { return collectionFields.contains(t + "." + f); }
    @Override public Collection<? extends Quizable> instances() { return instances; }
    @Override public Class<? extends Quizable> universe() { return Quizable.class; }
}
