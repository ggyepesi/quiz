package wikidata.explore.model;

public enum FieldCardinality {
    SINGLE,
    COLLECTION,
    AUTO;

    public boolean isCollection() {
        return this == COLLECTION;
    }

    @Override
    public String toString() {
        return switch (this) {
            case SINGLE -> "Single value";
            case COLLECTION -> "List";
            case AUTO -> "Auto-detect";
        };
    }
}
