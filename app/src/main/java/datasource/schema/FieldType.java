package datasource.schema;

/**
 * Provider-neutral value shapes a domain field can declare.
 *
 * <p>This vocabulary is shared by source offerings, editable models and compiled
 * execution plans. It therefore lives at the datasource/model boundary rather than
 * under any provider adapter.</p>
 */
public enum FieldType {
    STRING,
    IMAGE,
    ENTITY,
    NUMBER,
    DATE,
    TEXT,
    BOOLEAN,
    AUTO;

    @Override
    public String toString() {
        return switch (this) {
            case STRING -> "String";
            case IMAGE -> "Image";
            case ENTITY -> "Entity/Object";
            case NUMBER -> "Number";
            case DATE -> "Date";
            case TEXT -> "Text";
            case BOOLEAN -> "Boolean";
            case AUTO -> "Auto";
        };
    }
}
