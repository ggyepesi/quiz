package wikidata.explore.model;

public enum FieldSourceType {
    SPARQL,
    WIKIDATA_API,
    BACKLINKS,
    WIKIPEDIA_CATEGORY,
    WIKIPROJECT,
    COMMONS,
    MANUAL;

    public boolean implementedNow() {
        return this == SPARQL;
    }

    @Override
    public String toString() {
        return switch (this) {
            case SPARQL -> "SPARQL";
            case WIKIDATA_API -> "Wikidata API";
            case BACKLINKS -> "Backlinks";
            case WIKIPEDIA_CATEGORY -> "Wikipedia category";
            case WIKIPROJECT -> "WikiProject";
            case COMMONS -> "Commons";
            case MANUAL -> "Manual";
        };
    }
}