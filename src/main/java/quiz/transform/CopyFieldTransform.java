package quiz.transform;

public class CopyFieldTransform<S, T> implements ObjectTransform<S, T> {

    private final String sourcePath;
    private final String targetPath;

    public CopyFieldTransform(String sourcePath, String targetPath) {
        this.sourcePath = sourcePath;
        this.targetPath = targetPath;
    }

    @Override
    public void apply(S source, T target, TransformContext context) {
        Object value = FieldAccess.getPath(source, sourcePath);
        FieldAccess.setPath(target, targetPath, value);
    }
}