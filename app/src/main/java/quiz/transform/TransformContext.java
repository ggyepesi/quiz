package quiz.transform;

import objectview.ViewableAdapter;

import java.lang.reflect.Constructor;
import java.util.*;

public class TransformContext {

    private final Map<Object, Object> sourceToTarget = new IdentityHashMap<>();
    private final Map<Class<?>, List<Object>> targetsByClass = new LinkedHashMap<>();

    public <T> T getOrCreate(Object source, Class<T> targetClass) {
        if (source == null) {
            return null;
        }

        Object existing = sourceToTarget.get(source);
        if (existing != null) {
            return targetClass.cast(existing);
        }

        T created = newTargetInstance(targetClass);
        sourceToTarget.put(source, created);
        targetsByClass.computeIfAbsent(targetClass, k -> new ArrayList<>()).add(created);
        return created;
    }

    /** Register {@code source} as its own target of {@code targetClass} (identity /
     *  filter-only plans) — the member is the source itself, not a projection. */
    public void register(Object source, Class<?> targetClass) {
        if (source == null || sourceToTarget.containsKey(source)) {
            return;
        }
        sourceToTarget.put(source, source);
        targetsByClass.computeIfAbsent(targetClass, k -> new ArrayList<>()).add(source);
    }

    public <T> List<T> targets(Class<T> targetClass) {
        return targetsByClass.getOrDefault(targetClass, List.of())
                             .stream()
                             .map(targetClass::cast)
                             .toList();
    }

    private static <T> T newTargetInstance(Class<T> cls) {
        try {
            if (ViewableAdapter.class.isAssignableFrom(cls)) {
                @SuppressWarnings("unchecked")
                Class<? extends ViewableAdapter> viewableClass =
                        (Class<? extends ViewableAdapter>) cls;

                Constructor<? extends ViewableAdapter> ctor =
                        viewableClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                return cls.cast(ctor.newInstance());
            }

            Constructor<T> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();

        } catch (Exception e) {
            throw new RuntimeException(
                    "Target class needs accessible no-arg constructor: " + cls.getName(), e);
        }
    }
}
