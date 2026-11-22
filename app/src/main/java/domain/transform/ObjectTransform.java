package domain.transform;

public interface ObjectTransform<S, T> {
    void apply(S source, T target, TransformContext context);
}