package datasource.api;

import datasource.schema.FieldType;

/** Static output contract advertised before an operation is executed. */
public record SourceValueSchema(
        SourceValueKind kind,
        boolean collection,
        String referenceNamespace) {

    public SourceValueSchema {
        kind = kind == null ? SourceValueKind.UNKNOWN : kind;
        referenceNamespace = referenceNamespace == null ? "" : referenceNamespace.trim();
    }

    public static SourceValueSchema collection(SourceValueKind kind) {
        return new SourceValueSchema(kind, true, "");
    }

    public static SourceValueSchema collection(
            SourceValueKind kind, String referenceNamespace) {
        return new SourceValueSchema(kind, true, referenceNamespace);
    }

    /**
     * Why this operation cannot fill the declared model field, or an empty string when
     * their value shapes agree. MODEL_VALUE deliberately defers both decisions to the
     * configured field; concrete operations must agree on type and cardinality.
     */
    public String incompatibility(FieldType fieldType, boolean fieldCollection) {
        FieldType target = fieldType == null ? FieldType.AUTO : fieldType;
        if (!kind.bindableToField()) return kind + " is not a field value";
        if (kind == SourceValueKind.MODEL_VALUE) return "";
        if (collection != fieldCollection) {
            return "produces " + (collection ? "a list" : "one value")
                    + ", but the field is " + (fieldCollection ? "a list" : "one value");
        }
        FieldType produced = kind.fieldType();
        if (target != FieldType.AUTO && produced != FieldType.AUTO && produced != target) {
            return "produces " + produced + ", but the field is " + target;
        }
        return "";
    }
}
