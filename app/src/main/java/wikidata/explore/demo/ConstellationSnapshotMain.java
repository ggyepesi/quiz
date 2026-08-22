package wikidata.explore.demo;

import wikidata.WikidataIds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time data-prep tool: pulls the IAU constellations fresh from Wikidata via
 * a single SPARQL query and writes them as a {@link WikidataDynamicObject}
 * snapshot the web server can load offline. Deliberately bypasses the old,
 * slow {@code wikidata.stellar} downloader.
 *
 * <p>Each constellation becomes a dynamic object with scalar fields
 * (abbreviation, area) plus <i>reference</i> fields (hemisphere, namedAfter)
 * that are themselves dynamic objects — so the graph is navigable and
 * facetable. The sky chart is kept as an external image URL.
 */
public final class ConstellationSnapshotMain {

    private static final String ENDPOINT = "https://query.wikidata.org/sparql";

    private static final String QUERY = """
            SELECT ?c ?cLabel ?abbr ?area ?fam ?famLabel ?after ?afterLabel ?img WHERE {
              ?c wdt:P31 wd:Q8928 ; wdt:P1813 ?abbr ; wdt:P2046 ?area .
              OPTIONAL { ?c wdt:P361 ?fam. }
              OPTIONAL { ?c wdt:P138 ?after. }
              OPTIONAL { ?c wdt:P18  ?img. }
            %s
            } ORDER BY ?cLabel
            """.formatted(wikidata.query.LabelService.service());

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0]
                : aux.Constants.constellationsDataDirectory + "constellations.snapshot.json");

        JsonNode rows = runQuery();

        Map<String, WikidataDynamicObject> byQid = new LinkedHashMap<>();
        Map<String, WikidataDynamicObject> refs = new LinkedHashMap<>();

        for (JsonNode b : rows) {
            String qid = qidOf(val(b, "c"));
            if (qid == null) {
                continue;
            }
            WikidataDynamicObject c = byQid.computeIfAbsent(qid,
                    k -> new WikidataDynamicObject(k, val(b, "cLabel")));

            putScalar(c, "abbreviation", val(b, "abbr"));
            putScalar(c, "area (deg²)", val(b, "area"));
            putScalar(c, "chart", val(b, "img"));

            mergeRef(c, "hemisphere", val(b, "fam"), val(b, "famLabel"), refs);
            mergeRef(c, "named after", val(b, "after"), val(b, "afterLabel"), refs);
        }

        List<WikidataDynamicObject> objects = new ArrayList<>(byQid.values());
        new WikidataDynamicObjectJsonStore().save(objects, out);

        System.out.println("Wrote " + objects.size() + " constellations (+"
                + refs.size() + " referenced entities) to " + out);
    }

    private static JsonNode runQuery() throws Exception {
        String url = ENDPOINT + "?format=json&query="
                + URLEncoder.encode(QUERY, StandardCharsets.UTF_8);
        // One polite HTTP path (UrlOpener: contact UA, 429/5xx retry, redirects).
        try (java.io.InputStream in = objectview.utils.UrlOpener.open(url)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new ObjectMapper().readTree(body).path("results").path("bindings");
        }
    }

    private static void putScalar(WikidataDynamicObject o, String field, String value) {
        if (value != null && !value.isBlank()) {
            o.put(field, value);
        }
    }

    // A referenced entity becomes its own dynamic object (shared by qid) so the
    // web can navigate to it and facet by it.
    private static void mergeRef(WikidataDynamicObject owner, String field,
                                 String uri, String label,
                                 Map<String, WikidataDynamicObject> refs) {
        String qid = qidOf(uri);
        if (qid == null) {
            return;
        }
        WikidataDynamicObject ref = refs.computeIfAbsent(qid,
                k -> new WikidataDynamicObject(k, label == null || label.isBlank() ? k : label));
        owner.merge(field, ref);
    }

    private static String val(JsonNode binding, String key) {
        JsonNode n = binding.path(key).path("value");
        return n.isMissingNode() ? null : n.asText();
    }

    // "http://www.wikidata.org/entity/Q10576" -> "Q10576"; null for non-entities.
    private static String qidOf(String uri) {
        if (uri == null) {
            return null;
        }
        int slash = uri.lastIndexOf('/');
        String tail = slash >= 0 ? uri.substring(slash + 1) : uri;
        return WikidataIds.isQid(tail) ? tail : null;
    }

    private ConstellationSnapshotMain() {}
}
