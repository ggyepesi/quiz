package wikidata.explore.query.core;

import work.LogNode;
import work.RequestLinks;

/**
 * What a logged Wikidata or DBpedia request browses to.
 *
 * <p>This lived in {@code work.LogNode}, matching substrings of a step's {@code queryType} to
 * build {@code query.wikidata.org} and {@code dbpedia.org} URLs — endpoint knowledge in the
 * one package that is meant to have none, and reachable only by these two sources. Here it is
 * the Wikidata layer answering for its own endpoints, and a third source can answer for its
 * own without touching the log.
 */
public final class WikidataRequestLinks implements RequestLinks {

    /** Installs these rules for the process. Called wherever this source is bound into a
     *  context ({@link WikidataAccess#of}), because that is when its requests exist. */
    public static void install() {
        LogNode.linksProvidedBy(new WikidataRequestLinks());
    }

    @Override
    public Resolved forRequest(String queryType, String request) {
        if (queryType == null || request == null || request.isBlank()) return null;
        String type = queryType.toLowerCase();

        // An action-API request (wbgetentities, …) is an HTTP URL, not SPARQL — even when
        // the surrounding group inherited a "sparql" type, because sub-queries are logged
        // under that type by default. Detect it by content and correct the label, so the
        // reader is not told SPARQL and handed a query-service page that cannot open it.
        boolean apiRequest = request.contains("api.php")
                || request.contains("wbgetentities")
                || request.startsWith("http");
        if (type.contains("api") || apiRequest) {
            String url = firstHttpLine(request);
            if (url == null) return null;
            Resolved resolved = Resolved.of("Open request", sanitizeUrl(url));
            return apiRequest && !type.contains("api") ? resolved.labelledAs("API") : resolved;
        }
        if (type.contains("sparql")) {
            Datasource datasource = type.contains("dbpedia")
                    ? Datasource.DBPEDIA : Datasource.WIKIDATA;
            return Resolved.of(datasource.browseLabel(), datasource.browseUrl(request));
        }
        return null;
    }

    /** Percent-encode what an api.php URL commonly carries unescaped and {@code java.net.URI}
     *  rejects — {@code ids=Q1|Q2}, {@code props=labels|claims} — so the browser can open it.
     *  Already-valid characters, including {@code %}-escapes, are left alone. */
    private static String sanitizeUrl(String url) {
        return url.replace(" ", "%20")
                .replace("|", "%7C")
                .replace("{", "%7B")
                .replace("}", "%7D")
                .replace("\\", "%5C")
                .replace("^", "%5E");
    }

    private static String firstHttpLine(String text) {
        for (String line : text.split("\n")) {
            String candidate = line.strip();
            if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                return candidate;
            }
        }
        return null;
    }
}
