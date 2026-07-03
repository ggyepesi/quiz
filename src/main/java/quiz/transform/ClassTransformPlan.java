package quiz.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public class ClassTransformPlan<S, T> {

    private final Class<S> sourceClass;
    private final Class<T> targetClass;
    private final List<ObjectTransform<S, T>> transforms = new ArrayList<>();

    // Only sources passing every filter produce a target (and so participate in
    // inverts / grouping) — e.g. keep only winners (won == true).
    private final List<Predicate<S>> filters = new ArrayList<>();

    public ClassTransformPlan(Class<S> sourceClass, Class<T> targetClass) {
        this.sourceClass = sourceClass;
        this.targetClass = targetClass;
    }

    /** Keep only sources matching {@code predicate}. */
    public ClassTransformPlan<S, T> where(Predicate<S> predicate) {
        if (predicate != null) {
            filters.add(predicate);
        }
        return this;
    }

    /** Keep only sources whose field at {@code path} equals {@code expected} —
     *  reflection-based, so it works on generated (runtime) classes too. */
    public ClassTransformPlan<S, T> whereFieldEquals(String path, Object expected) {
        return where(s -> Objects.equals(FieldAccess.getPath(s, path), expected));
    }

    public ClassTransformPlan<S, T> copy(String sourcePath, String targetPath) {
        transforms.add(new CopyFieldTransform<>(sourcePath, targetPath));
        return this;
    }

    public <R> ClassTransformPlan<S, T> invertReference(String sourceReferencePath,
                                                        Class<R> referencedTargetClass,
                                                        String referencedTargetCollectionField) {
        transforms.add(new InvertReferenceTransform<>(
                sourceReferencePath,
                referencedTargetClass,
                referencedTargetCollectionField
        ));
        return this;
    }

    void applyIfMatches(Object source, TransformContext context) {
        if (!sourceClass.isInstance(source)) {
            return;
        }

        S s = sourceClass.cast(source);

        // Filtered-out sources produce NO target — so they don't materialize, and
        // an invert/collection built from targets never sees them.
        for (Predicate<S> filter : filters) {
            if (!filter.test(s)) {
                return;
            }
        }

        T target = context.getOrCreate(s, targetClass);

        for (ObjectTransform<S, T> transform : transforms) {
            transform.apply(s, target, context);
        }
    }

    public Class<T> targetClass() {
        return targetClass;
    }
}