package quiz.transform.app;

import java.util.List;

/**
 * A compiled domain class: its identity {@code className} (what instances stamp
 * and transforms/codegen match on), a display {@code displayName} (the model
 * alias when set, else the className), and its typed {@link ProductField}s
 * (declared fields plus structural markers). Produced once at transform-context
 * entry from the declared model, so consumers read typed classes instead of
 * inferring shape from raw instances.
 */
public record ProductClass(String className,
                           String displayName,
                           List<ProductField> fields) {

    public ProductField field(String name) {
        for (ProductField f : fields) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }
}
