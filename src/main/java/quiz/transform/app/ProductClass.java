package quiz.transform.app;

import objectview.field.FieldRef;
import objectview.field.FieldSchema;
import objectview.field.FieldKind;

import java.util.ArrayList;
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

    /** This class as a {@link FieldSchema} — the authoritative type source for a
     *  dynamic ({@code DynamicFieldSet}) instance of it. Structural markers are
     *  omitted (they're plumbing the pickers skip). */
    public FieldSchema asFieldSchema() {
        List<FieldRef> refs = new ArrayList<>();
        for (ProductField f : fields) {
            if (f.structural()) {
                continue;
            }
            FieldKind kind = f.collection() ? FieldKind.COLLECTION
                    : f.reference() ? FieldKind.REFERENCE
                    : FieldKind.ofTypeLabel(f.typeLabel());
            refs.add(FieldRef.of(f.name(), kind, f.typeLabel(),
                    f.reference(), f.collection(), false));
        }
        return () -> refs;
    }
}
