package wikidata.explore.model;

public enum FieldSourceType {
    SPARQL,
    DBPEDIA,
    WIKIPEDIA_INFOBOX,
    WIKIPEDIA_ARTICLE_INTRODUCTION,
    WIKIPEDIA_ARTICLE_LEAD_PARAGRAPH,
    WIKIPEDIA_ARTICLE_LEAD_SENTENCE,
    WIKIDATA_SITELINK_COUNT,
    WIKIDATA_INCOMING_COUNT,
    WIKIDATA_INHERITED_INCOMING_COUNT,
    WIKIDATA_API,
    BACKLINKS,
    WIKIPEDIA_CATEGORY,
    WIKIPROJECT,
    COMMONS,
    MANUAL;

    public boolean implementedNow() {
        return this == SPARQL || this == DBPEDIA || this == WIKIPEDIA_INFOBOX
                || wikipediaArticleText()
                || this == WIKIDATA_SITELINK_COUNT || this == WIKIDATA_INCOMING_COUNT
                || this == WIKIDATA_INHERITED_INCOMING_COUNT;
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
        return this == DBPEDIA || this == WIKIPEDIA_INFOBOX
                || wikipediaArticleText()
                || this == WIKIDATA_SITELINK_COUNT || this == WIKIDATA_INCOMING_COUNT
                || this == WIKIDATA_INHERITED_INCOMING_COUNT;
    }

    /** Whether this source asks the modeller to name a property/parameter. */
    public boolean usesPropertyInput() {
        return !wikipediaArticleText();
    }

    /** Whether the field editor can inspect samples and offer property choices. */
    public boolean supportsPropertyDiscovery() {
        return this == DBPEDIA || this == WIKIPEDIA_INFOBOX;
    }

    /** These are three projections of one retained article lead, not three fetchers. */
    public boolean wikipediaArticleText() {
        return this == WIKIPEDIA_ARTICLE_INTRODUCTION
                || this == WIKIPEDIA_ARTICLE_LEAD_PARAGRAPH
                || this == WIKIPEDIA_ARTICLE_LEAD_SENTENCE;
    }

    @Override
    public String toString() {
        return switch (this) {
            case SPARQL -> "SPARQL (Wikidata)";
            case DBPEDIA -> "DBpedia (Wikipedia infobox)";
            case WIKIPEDIA_INFOBOX -> "Wikipedia infobox value";
            case WIKIPEDIA_ARTICLE_INTRODUCTION -> "Wikipedia article introduction";
            case WIKIPEDIA_ARTICLE_LEAD_PARAGRAPH -> "Wikipedia lead paragraph";
            case WIKIPEDIA_ARTICLE_LEAD_SENTENCE -> "Wikipedia lead sentence";
            case WIKIDATA_SITELINK_COUNT -> "Wikidata sitelink count";
            case WIKIDATA_INCOMING_COUNT -> "Wikidata incoming relation count";
            case WIKIDATA_INHERITED_INCOMING_COUNT ->
                    "Wikidata inherited incoming relation count";
            case WIKIDATA_API -> "Wikidata API";
            case BACKLINKS -> "Backlinks";
            case WIKIPEDIA_CATEGORY -> "Wikipedia category";
            case WIKIPROJECT -> "WikiProject";
            case COMMONS -> "Commons";
            case MANUAL -> "Manual";
        };
    }
}
