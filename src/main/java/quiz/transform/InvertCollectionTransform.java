package quiz.transform;

import java.util.Collection;

public class InvertCollectionTransform<S, T, R> implements ObjectTransform<S, T> {

    private final String sourceCollectionPath;
    private final Class<R> referencedTargetClass;
    private final String referencedTargetCollectionField;

    public InvertCollectionTransform(String sourceCollectionPath,
                                     Class<R> referencedTargetClass,
                                     String referencedTargetCollectionField) {
        this.sourceCollectionPath = sourceCollectionPath;
        this.referencedTargetClass = referencedTargetClass;
        this.referencedTargetCollectionField = referencedTargetCollectionField;
    }

    @Override
    public void apply(S source, T target, TransformContext context) {
        Object value = FieldAccess.getPath(source, sourceCollectionPath);

        if (value == null) {
            return;
        }

        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalStateException(
                    "Path is not a collection: " + sourceCollectionPath
            );
        }

        for (Object referencedSource : collection) {
            if (referencedSource == null) {
                continue;
            }

            R referencedTarget =
                    context.getOrCreate(referencedSource, referencedTargetClass);

            FieldAccess.addToCollection(
                    referencedTarget,
                    referencedTargetCollectionField,
                    target
                                       );
        }
    }
}