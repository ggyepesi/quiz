package wikidata.query;

/**
 * The single home for the Wikidata label SERVICE, so the {@code en,mul} language
 * fallback lives in ONE place instead of drifting across query builders (see #90).
 *
 * <p>{@code mul} is Wikidata's "default for all languages" label. Many entities —
 * e.g. star "Albaldah" (Q14044), whose only Latin-script name lives in {@code mul},
 * or Oscar nominees/works with no English label — would otherwise resolve to a bare
 * QID. Every entity-name query should route through here so the fallback is uniform.
 */
public final class LabelService {

    private LabelService() {}

    public static final String DEFAULT_LANGUAGE = "en";

    /** The requested language(s) plus the {@code mul} fallback (idempotent). */
    public static String language(String lang) {
        String l = lang == null || lang.isBlank() ? DEFAULT_LANGUAGE : lang;
        return l.contains("mul") ? l : l + ",mul";
    }

    /** Automatic-mode SERVICE at the default language: labels every {@code ?xLabel}
     *  variable named in the SELECT. */
    public static String service() {
        return service(DEFAULT_LANGUAGE);
    }

    /** Automatic-mode SERVICE for {@code lang} (+ the {@code mul} fallback). */
    public static String service(String lang) {
        return "  SERVICE wikibase:label { bd:serviceParam wikibase:language \""
                + language(lang) + "\". }\n";
    }

    /** SERVICE binding an explicit {@code ?value rdfs:label ?label} — for {@code
     *  SELECT *} wrappers, where automatic mode (which needs the label var NAMED in
     *  the SELECT) would project nothing and every value renders as a bare QID. */
    public static String service(String lang, String valueVar, String labelVar) {
        return "  SERVICE wikibase:label {\n"
                + "    bd:serviceParam wikibase:language \"" + language(lang) + "\".\n"
                + "    ?" + valueVar + " rdfs:label ?" + labelVar + ".\n"
                + "  }\n";
    }
}
