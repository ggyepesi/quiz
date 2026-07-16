package quiz.transform;

import objectview.field.FieldAccess;

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

    // Identity/filter mode: the source IS the member (no projection), so filtered
    // instances keep their real display name + references — for filter-only views.
    private final boolean identity;

    public ClassTransformPlan(Class<S> sourceClass, Class<T> targetClass) {
        this(sourceClass, targetClass, false);
    }

    private ClassTransformPlan(Class<S> sourceClass, Class<T> targetClass,
                               boolean identity) {
        this.sourceClass = sourceClass;
        this.targetClass = targetClass;
        this.identity = identity;
    }

    /** A filter-only plan: matching sources are kept AS-IS as the members (no new
     *  target object), preserving their display name and references. */
    public static <X> ClassTransformPlan<X, X> keeping(Class<X> cls) {
        return new ClassTransformPlan<>(cls, cls, true);
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

    public <R> ClassTransformPlan<S, T> invertCollection(String sourceCollectionPath,
                                                         Class<R> referencedTargetClass,
                                                         String referencedTargetCollectionField) {
        transforms.add(new InvertCollectionTransform<>(
                sourceCollectionPath,
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

        // Filter-only: keep the source itself as the member (no projection).
        if (identity) {
            context.register(s, targetClass);
            return;
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