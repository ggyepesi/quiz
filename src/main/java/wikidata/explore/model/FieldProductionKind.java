package wikidata.explore.model;

public enum FieldProductionKind {
    INLINE_VALUE,
    DELAYED_ENTITY_FIELD,
    CHILD_OBJECTS,
    AUTO;

    @Override
    public String toString() {
        return switch (this) {
            case INLINE_VALUE -> "Simple property";
            case DELAYED_ENTITY_FIELD -> "Related entity values";
            case CHILD_OBJECTS -> "Related objects";
            case AUTO -> "Auto";
        };
    }
}
