package wikidata.explore.model;

public enum FieldType {
    STRING,
    IMAGE,
    ENTITY,
    NUMBER,
    DATE,
    TEXT,
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
            case AUTO -> "Auto";
        };
    }
}
