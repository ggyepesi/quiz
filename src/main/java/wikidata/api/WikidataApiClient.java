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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    private java.util.function.Consumer<String> log = s -> {};
    private final java.util.concurrent.atomic.AtomicLong requestSeq =
            new java.util.concurrent.atomic.AtomicLong();


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

        WikidataApiClient stapi = new WikidataApiClient("QuizProject/1.0");

        for (var result : stapi.searchEntities("star", 8)) {
            System.out.println(result);
        }
    }

    public WikidataApiClient(String userAgent) {
        this.userAgent = userAgent == null
                ? "WikidataApiClient/1.0" : userAgent;
    }

    public void log(java.util.function.Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
    }
    /**
     * Searches for Wikidata entities matching a natural language expression.
     *
     * Returns results ranked by relevance — the top result for "star" will be
     * Q523 (star, astronomical object), for "constellation" Q8928, etc.
     *
     * @param query    natural language search string, e.g. "star", "chemical element"
     * @param limit    number of results to return (max 50)
     */
    public List<SearchResult> searchEntities(
            String query, int limit) throws Exception {
        return searchEntities(query, limit, "item");
    }

    /** Search Wikidata entities of a given {@code type} ("item" or "property").
     *  Property search ({@code type=property}) lets the user find a PID by name
     *  (e.g. "nominated for" → P1411). */
    public List<SearchResult> searchEntities(
            String query, int limit, String type) throws Exception {

        Map<String, String> params = new LinkedHashMap<>();
        params.put("action",   "wbsearchentities");
        params.put("search",   encode(query));
        params.put("language", "en");
        params.put("type",     type == null || type.isBlank() ? "item" : type);
        params.put("limit",    String.valueOf(Math.min(limit, 50)));
        params.put("format",   "json");

        JsonNode root = get(WIKIDATA_API, params);
        List<SearchResult> results = new ArrayList<>();

        JsonNode search = root.path("search");
        if (search.isArray()) {
            for (JsonNode hit : search) {
                String qid         = hit.path("id").asText();
                String label       = hit.path("label").asText();
                String description = hit.path("description").asText();
                String url         = hit.path("url").asText();
                results.add(new SearchResult(qid, label, description, url));
            }
        }

        return results;
    }

    public record SearchResult(
            String qid,
            String label,
            String description,
            String url) {

        @Override
        public String toString() {
            return qid + "  " + label
                    + (description.isBlank() ? "" : "  — " + description);
        }
    }

    public record EntitySearchResult(
            String qid,
            String label,
            String description) {}
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
    // wbgetentities by QID — labels (+ selected claim PIDs as entity-QID lists)
    // ------------------------------------------------------------------

    /**
     * One entity as returned by {@link #getEntities}: its English label (blank if
     * absent) and, for each requested claim PID, the list of entity-QID values (in
     * document order, deprecated-rank statements skipped).
     */
    public record ApiEntity(
            String qid,
            String label,
            Map<String, List<String>> claimEntityQids) {

        /** The entity-QID values for a claim PID (empty if absent). */
        public List<String> claim(String pid) {
            return claimEntityQids.getOrDefault(pid, List.of());
        }
    }

    /**
     * Resolves entities by QID through the {@code wbgetentities} action API, in
     * batches of 50 (the API limit). Always returns English labels; when
     * {@code claimPids} is non-empty, {@code props=claims} is requested and each
     * listed PID's entity-QID values are extracted. This is the reliable,
     * non-SPARQL path for labels + outgoing entity claims (no query-engine timeout,
     * no full-index scan) — see docs/extraction-batched-membership.md, slice 3.
     */
    /** Per-batch log hook so a caller (e.g. the generation query log) can show each
     *  wbgetentities request as a structured entry. All three args are the request
     *  title, the request URL, and a completion summary ("N entities (ms)"/"FAILED…").
     *  Invoked from the fan-out threads — the sink must tolerate concurrency. */
    @FunctionalInterface
    public interface BatchLog {
        void logged(String title, String request, String summary);
    }

    public Map<String, ApiEntity> getEntities(
            List<String> qids, List<String> claimPids) throws Exception {
        return getEntities(qids, claimPids, null);
    }

    public Map<String, ApiEntity> getEntities(
            List<String> qids, List<String> claimPids, BatchLog batchLog)
            throws Exception {

        Map<String, ApiEntity> out = new ConcurrentHashMap<>();
        if (qids == null) return out;
        List<String> pids = claimPids == null ? List.of() : claimPids;
        boolean withClaims = !pids.isEmpty();
        List<String> clean = qids.stream()
                .filter(q -> q != null && q.matches("Q\\d+"))
                .distinct()
                .toList();
        if (clean.isEmpty()) return out;

        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < clean.size(); i += 50) {
            batches.add(clean.subList(i, Math.min(i + 50, clean.size())));
        }
        int total = batches.size();
        java.util.concurrent.atomic.AtomicInteger done =
                new java.util.concurrent.atomic.AtomicInteger();

        // The action API tolerates modest concurrency far better than WDQS did, so
        // fan the 50-QID batches out over a small pool. Per-batch best-effort with a
        // short retry: a transient failure drops only that batch (its members keep
        // their qid label / unfilled field), never the whole pass.
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(GET_ENTITIES_CONCURRENCY, batches.size()));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (List<String> batch : batches) {
                futures.add(pool.submit(() -> {
                    String url = entitiesUrl(batch, withClaims);
                    long t0 = System.nanoTime();
                    try {
                        JsonNode root = getEntitiesBatchWithRetry(batch, withClaims);
                        int before = out.size();
                        parseEntities(root, pids, out);
                        if (batchLog != null) {
                            batchLog.logged("wbgetentities " + done.incrementAndGet()
                                    + "/" + total, url, (out.size() - before)
                                    + " entities (" + ms(t0) + " ms)");
                        }
                    } catch (Exception e) {
                        if (batchLog != null) {
                            batchLog.logged("wbgetentities " + done.incrementAndGet()
                                    + "/" + total, url, "FAILED: " + e.getMessage()
                                    + " (" + ms(t0) + " ms)");
                        }
                        throw e;
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                    return out;
                } catch (ExecutionException e) {
                    log.accept("[API] wbgetentities batch failed: "
                            + e.getCause().getMessage() + "\n");
                }
            }
        } finally {
            pool.shutdown();
        }
        return out;
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    // The request as issued, with readable (decoded) pipes — for the query log.
    private static String entitiesUrl(List<String> qids, boolean withClaims) {
        return WIKIDATA_API + "?action=wbgetentities&ids=" + String.join("|", qids)
                + "&props=" + (withClaims ? "labels|claims" : "labels")
                + "&languages=en&format=json";
    }

    private static final int GET_ENTITIES_CONCURRENCY = 6;

    private JsonNode getEntitiesBatchWithRetry(
            List<String> qids, boolean withClaims) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return getEntitiesBatch(qids, withClaims);
            } catch (Exception e) {
                last = e;
                if (Thread.currentThread().isInterrupted()) throw e;
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
        throw last;
    }

    private JsonNode getEntitiesBatch(
            List<String> qids, boolean withClaims) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("action",    "wbgetentities");
        params.put("ids",       String.join("%7C", qids));
        params.put("props",     withClaims ? "labels%7Cclaims" : "labels");
        params.put("languages", "en");
        params.put("format",    "json");
        return get(WIKIDATA_API, params);
    }

    // Visible for WbGetEntitiesParseTest.
    static void parseEntities(
            JsonNode root, List<String> claimPids, Map<String, ApiEntity> out) {
        root.path("entities").fields().forEachRemaining(entry -> {
            JsonNode entity = entry.getValue();
            if (entity.has("missing")) return;
            String qid = entity.path("id").asText("");
            if (!qid.matches("Q\\d+")) return;

            String label = entity.path("labels").path("en").path("value").asText("");

            Map<String, List<String>> claims = new LinkedHashMap<>();
            for (String pid : claimPids) {
                List<String> vals = entityQids(entity.path("claims").path(pid));
                if (!vals.isEmpty()) claims.put(pid, vals);
            }
            out.put(qid, new ApiEntity(qid, label, claims));
        });
    }

    /** Every entity-QID value in a claims array, in order, skipping deprecated. */
    private static List<String> entityQids(JsonNode claimsArray) {
        if (!claimsArray.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode claim : claimsArray) {
            if ("deprecated".equals(claim.path("rank").asText())) continue;
            JsonNode val = claim.path("mainsnak").path("datavalue").path("value");
            String id = val.path("id").asText("");
            if (id.isBlank() && val.has("numeric-id")) {
                id = "Q" + val.path("numeric-id").asText();
            }
            if (id.matches("Q\\d+")) out.add(id);
        }
        return out;
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

        long id = requestSeq.incrementAndGet();
        long started = System.nanoTime();

        log.accept("\n[API " + id + "] GET " + url + "\n");

        java.net.URLConnection conn =
                new java.net.URL(url).openConnection();
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Accept",     "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);

        try (var stream = conn.getInputStream()) {
            JsonNode result = mapper.readTree(stream);

            log.accept("[API " + id + "] OK timeMs="
                               + (System.nanoTime() - started) / 1_000_000
                               + "\n");

            return result;
        } catch (IOException e) {
            log.accept("[API " + id + "] ERROR "
                               + e.getMessage()
                               + " timeMs="
                               + (System.nanoTime() - started) / 1_000_000
                               + "\n");

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
