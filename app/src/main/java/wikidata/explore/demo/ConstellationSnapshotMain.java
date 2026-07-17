package wikidata.explore.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private static final String UA = "QuizProject/1.0 (ggyepesi@gmail.com)";

    private static final String QUERY = """
            SELECT ?c ?cLabel ?abbr ?area ?fam ?famLabel ?after ?afterLabel ?img WHERE {
              ?c wdt:P31 wd:Q8928 ; wdt:P1813 ?abbr ; wdt:P2046 ?area .
              OPTIONAL { ?c wdt:P361 ?fam. }
              OPTIONAL { ?c wdt:P138 ?after. }
              OPTIONAL { ?c wdt:P18  ?img. }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
            } ORDER BY ?cLabel
            """;

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
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", UA)
                        .header("Accept", "application/sparql-results+json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("SPARQL " + resp.statusCode() + ": "
                    + resp.body().substring(0, Math.min(300, resp.body().length())));
        }
        return new ObjectMapper().readTree(resp.body()).path("results").path("bindings");
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
        return tail.matches("Q\\d+") ? tail : null;
    }

    private ConstellationSnapshotMain() {}
}
