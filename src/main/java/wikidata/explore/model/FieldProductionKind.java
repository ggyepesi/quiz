package wikidata.explore.model;

public enum FieldProductionKind {
    INLINE_VALUE,
    DELAYED_ENTITY_FIELD,
    CHILD_OBJECTS,
    /** DERIVED, not fetched: this field is the inverse of a forward reference on
     *  the referenced class — built in memory from data already generated, with no
     *  query. E.g. {@code Category.nominees} = the reverse of
     *  {@code Oscarnominations.categories}. */
    INVERT,
    AUTO;

    @Override
    public String toString() {
        return switch (this) {
            case INLINE_VALUE -> "Simple property";
            case DELAYED_ENTITY_FIELD -> "Related entity values";
            case CHILD_OBJECTS -> "Related objects";
            case INVERT -> "Invert (reverse of another field)";
            case AUTO -> "Auto";
        };
    }
}
