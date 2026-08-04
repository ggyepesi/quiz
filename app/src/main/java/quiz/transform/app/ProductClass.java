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
                           String baseClassName,
                           List<ProductField> fields) {

    public ProductClass(String className, String displayName,
                        List<ProductField> fields) {
        this(className, displayName, null, fields);
    }

    public ProductField field(String name) {
        for (ProductField f : fields) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }

    /** This class as a complete {@link FieldSchema}. Structural fields remain in
     *  the schema with an explicit role: persistence retains them while ordinary
     *  field pickers consistently omit them. */
    public FieldSchema asFieldSchema() {
        List<FieldRef> refs = new ArrayList<>();
        for (ProductField f : fields) {
            FieldKind kind = f.collection() ? FieldKind.COLLECTION
                    : f.reference() ? FieldKind.REFERENCE
                    : FieldKind.ofTypeLabel(f.typeLabel());
            FieldKind valueKind = f.reference() ? FieldKind.REFERENCE
                    : FieldKind.ofTypeLabel(f.typeLabel());
            refs.add(FieldRef.described(f.name(), kind, valueKind,
                    f.typeLabel(), f.reference(), f.collection(),
                    f.nestedClassName(), f.structural(), false,
                    false, false, "", false));
        }
        List<FieldRef> immutable = List.copyOf(refs);
        return () -> immutable;
    }
}
