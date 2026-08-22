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
        return serviceBinding(lang, "    ?" + valueVar + " rdfs:label ?" + labelVar + ".\n");
    }

    /** SERVICE with caller-written binding lines (e.g. a label AND a description on the
     *  same variable), so a second binding is not a reason to hand-roll the SERVICE. */
    public static String serviceBinding(String lang, String bindings) {
        return "  SERVICE wikibase:label {\n"
                + "    bd:serviceParam wikibase:language \"" + language(lang) + "\".\n"
                + (bindings == null ? "" : bindings)
                + "  }\n";
    }

    /** The {@code mwapi} search language. The API takes ONE language, so there is no
     *  {@code mul} fallback to add — but the choice still belongs here rather than
     *  being spelled again at each search site. */
    public static String searchLanguage(String lang) {
        return lang == null || lang.isBlank() ? DEFAULT_LANGUAGE : lang;
    }

    /**
     * The language filter for the OTHER label form — an explicit {@code ?x rdfs:label
     * ?xLabel} triple, which the builders use instead of the SERVICE for large result
     * sets. {@code = "en"} drops a mul-only label exactly the way the SERVICE did, so
     * the fallback has to be expressed here too, as a set membership.
     *
     * <p>Returns the empty string for a blank or {@code any} language, so a caller can
     * append it unconditionally.
     */
    public static String labelFilter(String labelVar, String lang) {
        if (lang != null && "any".equalsIgnoreCase(lang.trim())) {
            return "";
        }
        // Callers hold the variable in both spellings ("valueLabel" and "?valueLabel");
        // accepting either is what stops a second, subtly different helper appearing.
        String bare = labelVar == null ? "" : labelVar.trim();
        bare = bare.startsWith("?") ? bare.substring(1) : bare;
        StringBuilder in = new StringBuilder();
        for (String l : language(lang).split(",")) {
            if (in.length() > 0) {
                in.append(", ");
            }
            in.append('"').append(l.trim()).append('"');
        }
        return "FILTER(LANG(?" + bare + ") IN (" + in + "))";
    }
}
