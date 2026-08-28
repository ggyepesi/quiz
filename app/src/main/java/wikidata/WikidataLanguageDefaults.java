package wikidata;

/**
 * The one translation between the application's default language and Wikidata's
 * representations of it.  Query APIs use the language code while claim qualifiers
 * use the language item; callers should not have to keep {@code en} and {@code Q1860}
 * aligned independently.
 */
public final class WikidataLanguageDefaults {

    private WikidataLanguageDefaults() {}

    public static final String CODE = "en";
    public static final String ENTITY_QID = "Q1860";
    public static final String QUALIFIER_PID = "P407";

    /** Wikibase label fallback in the comma-separated form used by SPARQL. */
    public static String languages() {
        return CODE + ",mul";
    }

    /** The same preference in the pipe-separated form used by wbgetentities. */
    public static String apiLanguages() {
        return languages().replace(',', '|');
    }

    /** Wikimedia's site key for the Wikipedia matching the default language. */
    public static String wikipediaSite() {
        return CODE + "wiki";
    }

    /**
     * Wikidata item used to project language-qualified claim values. Blank disables
     * projection; the shared default code and an explicit language QID are accepted.
     */
    public static String entityQid(String code) {
        String configured = code == null ? "" : code.trim();
        if (configured.isBlank()) return "";
        if (CODE.equalsIgnoreCase(configured)) return ENTITY_QID;
        if (WikidataIds.isQid(configured)) return configured;
        throw new IllegalArgumentException("Value language must be '" + CODE
                + "' or a Wikidata language QID, not '" + configured + "'");
    }
}
