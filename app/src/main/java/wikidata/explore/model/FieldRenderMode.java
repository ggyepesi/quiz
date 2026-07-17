package wikidata.explore.model;

public enum FieldRenderMode {
    AUTO,
    INLINE,
    REFERENCE;

    @Override
    public String toString() {
        return switch (this) {
            case AUTO -> "Auto";
            case INLINE -> "Inline";
            case REFERENCE -> "Reference";
        };
    }
}