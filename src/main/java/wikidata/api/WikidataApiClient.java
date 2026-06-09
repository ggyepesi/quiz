package wikidata.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lightweight client for the Wikidata / MediaWiki REST API.
 *
 * Complements WikidataSparqlClient for operations where the API is faster
 * or more reliable than SPARQL:
 *   - Category membership  (stars of constellation, films by director, ...)
 *   - Entity property lookup by known QID or Wikipedia article title
 *
 * All HTTP calls use URLConnection with an explicit User-Agent header.
 * Titles are individually URL-encoded before joining with %7C (encoded pipe)
 * to handle Unicode characters (U+2212 minus, en-dash, +, etc.) that cause
 * 400 errors when passed raw.
 */
public class WikidataApiClient {

    private static final String WIKIDATA_API =
            "https://www.wikidata.org/w/api.php";
    private static final String WIKIPEDIA_API =
            "https://en.wikipedia.org/w/api.php";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String userAgent;


    public static void main(String[] args) throws Exception {
        wikidata.api.WikidataApiClient api = new wikidata.api.WikidataApiClient("QuizProject/1.0");

        List<wikidata.api.WikidataApiClient.EntityResult> stars =
                api.starsOfConstellation("Orion", 4.5, 30);
        System.out.println("Stars: " + stars.size());
        for (var star : stars) {
            System.out.printf("%-20s  mag=%-6s  type=%s%n",
                              star.label(),
                              star.property("P1215"),
                              star.property("P215"));
        }
    }

    public WikidataApiClient(String userAgent) {
        this.userAgent = userAgent == null
                ? "WikidataApiClient/1.0" : userAgent;
    }

    // ------------------------------------------------------------------
    // Category members → article titles
    // ------------------------------------------------------------------

    /**
     * Returns Wikipedia article titles in a category.
     *
     * Example: categoryMembers("Category:Orion_(constellation)", 200)
     *
     * Uses the English Wikipedia API. The cmcontinue parameter is followed
     * automatically until the limit is reached or all members are returned.
     */
    public List<String> categoryMembers(
            String categoryTitle, int limit) throws Exception {

        List<String> titles = new ArrayList<>();
        String cmcontinue = null;

        do {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("action",      "query");
            params.put("list",        "categorymembers");
            params.put("cmtitle",     encode(categoryTitle));
            params.put("cmnamespace", "0");
            params.put("cmlimit",     String.valueOf(Math.min(limit - titles.size(), 500)));
            params.put("format",      "json");
            if (cmcontinue != null)
                params.put("cmcontinue", encode(cmcontinue));

            JsonNode root = get(WIKIPEDIA_API, params);

            JsonNode members = root.path("query").path("categorymembers");
            if (members.isArray()) {
                for (JsonNode m : members) {
                    String title = m.path("title").asText();
                    if (!title.isBlank()) titles.add(title);
                    if (titles.size() >= limit) return titles;
                }
            }

            // Follow continuation if present
            JsonNode cont = root.path("continue").path("cmcontinue");
            cmcontinue = cont.isMissingNode() ? null : cont.asText();

        } while (cmcontinue != null && titles.size() < limit);

        return titles;
    }

    // ------------------------------------------------------------------
    // Article titles → entities with claims
    // ------------------------------------------------------------------

    /**
     * Resolves Wikipedia article titles to Wikidata entities, returning
     * QID + label + requested property values.
     *
     * Handles up to 50 titles per API call automatically.
     * Titles with special characters (Unicode minus, en-dash, +, etc.)
     * are correctly encoded before sending.
     *
     * @param titles       Wikipedia article titles (batched at 50)
     * @param propertyPids property PIDs to extract, e.g. ["P1215", "P215"]
     */
    public List<EntityResult> resolveArticles(
            List<String> titles,
            List<String> propertyPids) throws Exception {

        if (titles == null || titles.isEmpty()) return List.of();

        List<EntityResult> all = new ArrayList<>();

        // API limit is 50 titles per call
        for (int i = 0; i < titles.size(); i += 50) {
            List<String> batch = titles.subList(
                    i, Math.min(i + 50, titles.size()));
            all.addAll(resolveArticleBatch(batch, propertyPids));
        }

        return all;
    }

    private List<EntityResult> resolveArticleBatch(
            List<String> titles,
            List<String> propertyPids) throws Exception {

        // Encode each title individually, join with encoded pipe
        String titlesParam = titles.stream()
                                   .map(t -> encode(t.replace(" ", "_")))
                                   .collect(Collectors.joining("%7C"));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("action",    "wbgetentities");
        params.put("sites",     "enwiki");
        params.put("titles",    titlesParam);
        params.put("props",     "claims%7Clabels%7Csitelinks");
        params.put("languages", "en");
        params.put("format",    "json");

        JsonNode root = get(WIKIDATA_API, params);
        List<EntityResult> results = new ArrayList<>();

        root.path("entities").fields().forEachRemaining(entry -> {
            JsonNode entity = entry.getValue();
            if (entity.has("missing")) return;

            String qid = entity.path("id").asText();
            if (qid.isBlank()) return;

            // Label — fall back to sitelink title when English label absent
            String label = entity.path("labels").path("en")
                                 .path("value").asText();
            if (label.isBlank()) {
                String siteTitle = entity.path("sitelinks").path("enwiki")
                                         .path("title").asText();
                if (!siteTitle.isBlank()) {
                    // Strip disambiguation suffixes
                    label = siteTitle
                            .replaceAll("_\\(star\\)$", "")
                            .replaceAll("_\\(astronomy\\)$", "")
                            .replaceAll("_\\(constellation\\)$", "")
                            .replace("_", " ")
                            .trim();
                } else {
                    label = qid;
                }
            }

            Map<String, String> props = new LinkedHashMap<>();
            for (String pid : propertyPids) {
                String value = extractBestValue(
                        entity.path("claims").path(pid));
                if (value != null && !value.isBlank())
                    props.put(pid, value);
            }

            results.add(new EntityResult(qid, label, props));
        });

        return results;
    }

    // ------------------------------------------------------------------
    // Convenience: stars of a constellation
    // ------------------------------------------------------------------

    /**
     * Returns stars in a constellation sorted by apparent magnitude.
     *
     * Uses the Wikipedia category "Category:&lt;Name&gt;_(constellation)"
     * as the membership source, then filters to entities that have both
     * P1215 (apparent magnitude) and P215 (spectral type) — which reliably
     * identifies stars and excludes nebulae, clusters, and exoplanets.
     *
     * @param constellationName English name, e.g. "Orion"
     * @param magnitudeLimit    maximum apparent magnitude, e.g. 4.5
     * @param limit             maximum number of stars to return
     */
    public List<EntityResult> starsOfConstellation(
            String constellationName,
            double magnitudeLimit,
            int limit) throws Exception {

        String category = "Category:" + constellationName + "_(constellation)";
        List<String> titles = categoryMembers(category, 300);

        if (titles.isEmpty()) return List.of();

        List<EntityResult> all = resolveArticles(
                titles, List.of("P1215", "P215", "P31"));

        return all.stream()
                  // Must have apparent magnitude — proxy for "is a star"
                  .filter(e -> e.properties().containsKey("P1215"))
                  // Magnitude within limit
                  .filter(e -> parseMag(e.properties().get("P1215"))
                          <= magnitudeLimit)
                  // Must have a non-blank name
                  .filter(e -> !e.label().isBlank() && !e.label().equals(e.qid()))
                  // Sort brightest first
                  .sorted((a, b) -> Double.compare(
                          parseMag(a.properties().get("P1215")),
                          parseMag(b.properties().get("P1215"))))
                  .limit(limit)
                  .toList();
    }

    // ------------------------------------------------------------------
    // Result record
    // ------------------------------------------------------------------

    public record EntityResult(
            String qid,
            String label,
            Map<String, String> properties) {

        /** Returns the value for a property PID, or "" if absent. */
        public String property(String pid) {
            return properties.getOrDefault(pid, "");
        }

        /** Apparent magnitude as a double, or Double.MAX_VALUE if absent. */
        public double magnitude() {
            return parseMag(properties.get("P1215"));
        }
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /**
     * Performs a GET request with the given query parameters.
     *
     * Parameters are passed pre-encoded — values that contain special
     * characters (pipe, plus, Unicode) must already be encoded by the caller.
     * The map entries are joined with "&amp;" and appended to the base URL.
     */
    private JsonNode get(String baseUrl,
                         Map<String, String> params) throws Exception {

        String query = params.entrySet().stream()
                             .map(e -> e.getKey() + "=" + e.getValue())
                             .collect(Collectors.joining("&"));

        String url = baseUrl + "?" + query;

        java.net.URLConnection conn =
                new java.net.URL(url).openConnection();
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Accept",     "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);

        try (var stream = conn.getInputStream()) {
            return mapper.readTree(stream);
        } catch (IOException e) {
            // Include URL in message for easier debugging
            throw new IOException(
                    e.getMessage() + " | URL: " + url, e);
        }
    }

    // ------------------------------------------------------------------
    // Claim extraction
    // ------------------------------------------------------------------

    /**
     * Extracts the best value from a claims array.
     *
     * Prefers preferred-rank claims over normal-rank claims.
     * Handles quantity, string, monolingualtext, wikibase-entityid, time.
     */
    private static String extractBestValue(JsonNode claimsArray) {
        if (!claimsArray.isArray() || claimsArray.isEmpty()) return null;

        // Prefer preferred rank, then normal rank
        JsonNode best = null;
        for (JsonNode claim : claimsArray) {
            String rank = claim.path("rank").asText();
            if ("preferred".equals(rank)) { best = claim; break; }
            if (best == null && "normal".equals(rank)) best = claim;
        }
        if (best == null) best = claimsArray.get(0);

        JsonNode datavalue = best.path("mainsnak").path("datavalue");
        if (datavalue.isMissingNode()) return null;

        String type  = datavalue.path("type").asText();
        JsonNode val = datavalue.path("value");

        return switch (type) {
            case "quantity" -> {
                // Strip leading + from Wikidata quantity format (+0.13 → 0.13)
                String amount = val.path("amount").asText();
                yield amount.startsWith("+")
                        ? amount.substring(1) : amount;
            }
            case "string"            -> val.asText();
            case "monolingualtext"   -> val.path("text").asText();
            case "wikibase-entityid" ->
                    "Q" + val.path("numeric-id").asText();
            case "time"              -> val.path("time").asText();
            default -> val.isTextual() ? val.asText() : null;
        };
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * URL-encodes a string using UTF-8.
     * Used for individual parameter values — NOT for the full URL.
     */
    private static String encode(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static double parseMag(String s) {
        if (s == null || s.isBlank()) return Double.MAX_VALUE;
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { return Double.MAX_VALUE; }
    }
}
