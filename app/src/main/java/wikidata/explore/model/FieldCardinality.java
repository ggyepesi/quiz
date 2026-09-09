package wikidata.explore.model;

public enum FieldCardinality {
    SINGLE,
    COLLECTION;

    /** Older saved models used AUTO even though every runtime consumer treated it
     * as SINGLE. Accept that spelling only at the JSON boundary; it is no longer a
     * model state. */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static FieldCardinality fromJson(String value) {
        return value == null || value.isBlank() || "AUTO".equalsIgnoreCase(value)
                ? SINGLE : valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }

    public boolean isCollection() {
        return this == COLLECTION;
    }

    @Override
    public String toString() {
        return switch (this) {
            case SINGLE -> "Single value";
            case COLLECTION -> "List";
        };
    }
}
