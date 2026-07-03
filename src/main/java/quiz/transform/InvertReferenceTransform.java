package quiz.transform;

public class InvertReferenceTransform<S, T, R> implements ObjectTransform<S, T> {

    private final String sourceReferencePath;
    private final Class<R> referencedTargetClass;
    private final String referencedTargetCollectionField;

    public InvertReferenceTransform(String sourceReferencePath,
                                    Class<R> referencedTargetClass,
                                    String referencedTargetCollectionField) {
        this.sourceReferencePath = sourceReferencePath;
        this.referencedTargetClass = referencedTargetClass;
        this.referencedTargetCollectionField = referencedTargetCollectionField;
    }

    @Override
    public void apply(S source, T target, TransformContext context) {
        Object referencedSource = FieldAccess.getPath(source, sourceReferencePath);
        if (referencedSource == null) {
            return;
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