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

    /**
     * Whether this source is read AFTER Wikidata extraction rather than compiled into the
     * Wikidata rule tree — which also means its "property" is not a Wikidata PID, so
     * nothing may inspect it as one.
     *
     * <p>Asked here because every caller had been enumerating the types instead, and
     * adding a source meant finding all of them: the rule compiler carried the list
     * twice and the field editor carried a one-name-shorter version of it, which is how
     * an infobox parameter reached a routine that only understands Pxx.
     */
    public boolean filledAfterExtraction() {
        return this == DBPEDIA || this == WIKIPEDIA_INFOBOX;
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
