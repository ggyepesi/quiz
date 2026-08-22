package wikidata.explore.model;

public enum FieldSourceType {
    SPARQL,
    DBPEDIA,
    WIKIPEDIA_INFOBOX,
    WIKIDATA_API,
    BACKLINKS,
    WIKIPEDIA_CATEGORY,
    WIKIPROJECT,
    COMMONS,
    MANUAL;

    public boolean implementedNow() {
        return this == SPARQL || this == DBPEDIA || this == WIKIPEDIA_INFOBOX;
    }

    @Override
    public String toString() {
        return switch (this) {
            case SPARQL -> "SPARQL (Wikidata)";
            case DBPEDIA -> "DBpedia (Wikipedia infobox)";
            case WIKIPEDIA_INFOBOX -> "Wikipedia infobox parameter";
            case WIKIDATA_API -> "Wikidata API";
            case BACKLINKS -> "Backlinks";
            case WIKIPEDIA_CATEGORY -> "Wikipedia category";
            case WIKIPROJECT -> "WikiProject";
            case COMMONS -> "Commons";
            case MANUAL -> "Manual";
        };
    }
}
