package wikidata.api;

import batch.BatchExecutor;
import batch.BatchPolicy;
import batch.BatchProgress;
import batch.WorkDescriptor;
import batch.WorkUnit;
import objectview.utils.RetryAfter;
import wikidata.WikidataIds;
import wikidata.WikidataBatchFailureClassifier;

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

    public static final String DEFAULT_USER_AGENT =
            "QuizProject/1.0 (ggyepesi@gmail.com)";

    private static final String WIKIDATA_API =
            "https://www.wikidata.org/w/api.php";
    private static final String WIKIPEDIA_API =
            "https://en.wikipedia.org/w/api.php";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String userAgent;
    private process.CancellationToken cancellation = new process.CancellationToken();

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

    public WikidataApiClient cancellation(process.CancellationToken token) {
        cancellation = token == null ? new process.CancellationToken() : token;
        return this;
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
            Map<String, List<String>> claimEntityQids,
            boolean missing,
            Map<String, String> valuelessClaims) {

        public ApiEntity(
                String qid,
                String label,
                Map<String, List<String>> claimEntityQids) {
            this(qid, label, claimEntityQids, false, Map.of());
        }

        public ApiEntity(
                String qid,
                String label,
                Map<String, List<String>> claimEntityQids,
                boolean missing) {
            this(qid, label, claimEntityQids, missing, Map.of());
        }

        public ApiEntity {
            valuelessClaims = valuelessClaims == null ? Map.of()
                    : Map.copyOf(valuelessClaims);
        }

        /**
         * The snak type when this property has statements but NONE of them carries a
         * value — {@code somevalue} (a value exists, unknown) or {@code novalue} (there
         * is none). Null when the property yielded values, or has no statement at all.
         *
         * <p>The distinction the caller needs: "the source has nothing to say" and "the
         * source says it is unknown" are different answers, and only the first is a gap
         * worth asking about again.
         */
        public String valuelessClaim(String pid) {
            return valuelessClaims.get(pid);
        }

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
     *  Attempts fan out, while completed results are reported by the executor's
     *  coordinating thread in stable commit order. */
    @FunctionalInterface
    public interface BatchLog {
        void logged(String title, String request, String summary);

        /**
         * A batch the server refused. Separate from {@link #logged} because a sink
         * records STATUS, not just text: routed through logged(), a failed batch
         * became an OK entry whose summary happened to start with "FAILED", so a run
         * with 54 refused batches read as clean unless someone grepped the text.
         *
         * <p>Defaults to {@code logged} so a sink that does not distinguish the two
         * behaves as before rather than losing the entry.
         */
        default void failed(String title, String request, String error) {
            logged(title, request, error);
        }
    }

    public Map<String, ApiEntity> getEntities(
            List<String> qids, List<String> claimPids) throws Exception {
        return getEntities(qids, claimPids, null);
    }

    public Map<String, ApiEntity> getEntities(
            List<String> qids, List<String> claimPids, BatchLog batchLog)
            throws Exception {

        Map<String, ApiEntity> out = new LinkedHashMap<>();
        if (qids == null) return out;
        List<String> pids = claimPids == null ? List.of() : claimPids;
        List<WorkUnit<Map<String, ApiEntity>>> roots = entityUnits(qids, pids);
        entityExecutor(batchLog).run(roots,
                (descriptor, entities) -> out.putAll(entities));
        return out;
    }

    /** What a best-effort resolve came back with, and how many adaptively narrowed
     *  leaf batches did not. A caller that can live without some answers needs both: the
     *  entities to use, and the count to say honestly what is missing. */
    public record PartialEntities(
            Map<String, ApiEntity> entities,
            int failedBatches,
            List<String> unavailableQids) {
        public PartialEntities(Map<String, ApiEntity> entities, int failedBatches) {
            this(entities, failedBatches, List.of());
        }
        public PartialEntities {
            unavailableQids = unavailableQids == null
                    ? List.of() : List.copyOf(unavailableQids);
        }
    }

    /**
     * Like {@link #getEntities} but keeps what the successful batches returned
     * instead of failing the call.
     *
     * <p>Only for callers whose result is genuinely optional — label resolution is
     * the case: an unresolved reference keeps its QID as its name, which is a
     * degraded label, not a wrong answer. It is NOT for claims. A missing claim is
     * indistinguishable from an absent one, and treating a throttled batch as "this
     * entity has no P840" is how a fetch failure becomes false data.
     *
     * <p>The isolation lives here rather than in the caller so the shared executor can
     * fan batches out, retry them consistently, and narrow oversized failures.
     */
    public PartialEntities getEntitiesBestEffort(
            List<String> qids, List<String> claimPids, BatchLog batchLog)
            throws Exception {

        Map<String, ApiEntity> out = new LinkedHashMap<>();
        if (qids == null) return new PartialEntities(out, 0);
        List<String> pids = claimPids == null ? List.of() : claimPids;
        List<WorkDescriptor> failed = entityExecutor(batchLog).runBestEffort(
                entityUnits(qids, pids),
                (descriptor, entities) -> out.putAll(entities));
        return new PartialEntities(out, failed.size(), unavailableQids(failed));
    }

    /** Partial CLAIM lookup for callers that preserve the previous state of every
     * entity absent from the result. Unlike an optional-label caller, such a caller
     * must report unavailable entities separately and must never interpret absence as
     * "the claim is absent". Evidence classification uses exactly that contract. */
    public PartialEntities getEntityClaimsPartial(
            List<String> qids, List<String> claimPids, BatchLog batchLog)
            throws Exception {
        Map<String, ApiEntity> out = new LinkedHashMap<>();
        if (qids == null) return new PartialEntities(out, 0);
        List<String> pids = claimPids == null ? List.of() : claimPids;
        List<WorkUnit<Map<String, ApiEntity>>> roots = entityUnits(qids, pids);
        BatchExecutor<Map<String, ApiEntity>> executor = entityExecutor(batchLog);
        List<WorkDescriptor> failed = executor.runBestEffort(roots,
                (descriptor, entities) -> out.putAll(entities));
        return new PartialEntities(out, failed.size(), unavailableQids(failed));
    }

    private List<WorkUnit<Map<String, ApiEntity>>> entityUnits(
            List<String> qids, List<String> pids) {
        List<String> clean = qids.stream()
                .filter(q -> q != null && WikidataIds.isQid(q)).distinct().toList();
        List<WorkUnit<Map<String, ApiEntity>>> roots = new ArrayList<>();
        for (int i = 0; i < clean.size(); i += 50) {
            roots.add(entityUnit(List.copyOf(clean.subList(i,
                    Math.min(i + 50, clean.size()))), pids));
        }
        return roots;
    }

    private BatchExecutor<Map<String, ApiEntity>> entityExecutor(BatchLog batchLog) {
        return new BatchExecutor<>(BatchPolicy.defaults().withResume(false),
                batchProgress(batchLog), WikidataBatchFailureClassifier.INSTANCE,
                cancellation, batch.BatchCheckpointStore.NONE, GET_ENTITIES_CONCURRENCY);
    }

    private WorkUnit<Map<String, ApiEntity>> entityUnit(
            List<String> qids, List<String> pids) {
        boolean withClaims = !pids.isEmpty();
        return new WorkUnit<>() {
            @Override public WorkDescriptor descriptor() {
                String ids = String.join(",", qids);
                String mode = withClaims ? "claims" : "labels";
                return new WorkDescriptor("wbgetentities-" + mode, mode + ":" + ids,
                        "wbgetentities " + qids.size() + " entities",
                        Map.of("ids", ids, "claims", String.join(",", pids)));
            }
            @Override public String request() { return entitiesUrl(qids, withClaims); }
            @Override public Map<String, ApiEntity> execute() throws Exception {
                Map<String, ApiEntity> result = new LinkedHashMap<>();
                parseEntities(getEntitiesBatchWithRetry(qids, withClaims), pids, result);
                return result;
            }
            @Override public List<? extends WorkUnit<Map<String, ApiEntity>>> split() {
                if (qids.size() < 2) return List.of();
                int middle = qids.size() / 2;
                return List.of(entityUnit(qids.subList(0, middle), pids),
                        entityUnit(qids.subList(middle, qids.size()), pids));
            }
        };
    }

    private static List<String> unavailableQids(List<WorkDescriptor> failed) {
        return failed.stream().flatMap(descriptor -> java.util.Arrays.stream(
                        descriptor.parameters().getOrDefault("ids", "").split(",")))
                .filter(WikidataIds::isQid).distinct().toList();
    }

    /**
     * Per entity QID, its statements for {@code statementPid} — each carrying the
     * mainsnak value and the requested {@code qualifierPids}' raw values. This is the
     * reliable, non-SPARQL source for reification: the same statement+qualifier data
     * a {@code ?e p:Pxxx ?st . ?st ps:Pxxx ?value . OPTIONAL {?st pq:Pyyy ?q}} query
     * returns, read straight off the wbgetentities claims (which carry qualifiers).
     * QIDs only — the value/qualifier entity labels are resolved separately.
     */
    public Map<String, List<ApiStatement>> getStatements(
            List<String> entityQids, String statementPid,
            List<String> qualifierPids, BatchLog batchLog) throws Exception {

        Map<String, List<ApiStatement>> out = new LinkedHashMap<>();
        if (entityQids == null || statementPid == null || statementPid.isBlank()) {
            return out;
        }
        List<String> quals = qualifierPids == null ? List.of() : qualifierPids;
        List<String> clean = entityQids.stream()
                .filter(q -> q != null && WikidataIds.isQid(q)).distinct().toList();
        List<WorkUnit<Map<String, List<ApiStatement>>>> roots = new ArrayList<>();
        for (int i = 0; i < clean.size(); i += 50) {
            roots.add(statementUnit(List.copyOf(clean.subList(i,
                    Math.min(i + 50, clean.size()))), statementPid, quals));
        }
        BatchExecutor<Map<String, List<ApiStatement>>> executor = new BatchExecutor<>(
                BatchPolicy.defaults().withResume(false), batchProgress(batchLog),
                WikidataBatchFailureClassifier.INSTANCE,
                cancellation, batch.BatchCheckpointStore.NONE,
                GET_ENTITIES_CONCURRENCY);
        executor.run(roots, (descriptor, statements) -> out.putAll(statements));
        return out;
    }

    private WorkUnit<Map<String, List<ApiStatement>>> statementUnit(
            List<String> qids, String statementPid, List<String> qualifierPids) {
        return new WorkUnit<>() {
            @Override public WorkDescriptor descriptor() {
                String ids = String.join(",", qids);
                return new WorkDescriptor("wbgetentities-statements",
                        "statements:" + statementPid + ":" + ids,
                        "wbgetentities " + qids.size() + " entities",
                        Map.of("ids", ids, "statement", statementPid,
                                "qualifiers", String.join(",", qualifierPids)));
            }
            @Override public String request() { return entitiesUrl(qids, true); }
            @Override public Map<String, List<ApiStatement>> execute() throws Exception {
                Map<String, List<ApiStatement>> result = new LinkedHashMap<>();
                parseStatements(getEntitiesBatchWithRetry(qids, true), statementPid,
                        qualifierPids, result);
                return result;
            }
            @Override public List<? extends WorkUnit<Map<String, List<ApiStatement>>>> split() {
                if (qids.size() < 2) return List.of();
                int middle = qids.size() / 2;
                return List.of(statementUnit(qids.subList(0, middle), statementPid,
                                qualifierPids),
                        statementUnit(qids.subList(middle, qids.size()), statementPid,
                                qualifierPids));
            }
        };
    }

    private static BatchProgress batchProgress(BatchLog batchLog) {
        if (batchLog == null) return BatchProgress.NOOP;
        return (title, request) -> new BatchProgress.Running() {
            private final long started = System.nanoTime();
            private final List<String> details = new ArrayList<>();
            @Override public void detail(String text) { details.add(text); }
            @Override public void done(String summary) {
                String prefix = details.isEmpty() ? ""
                        : String.join("\n", details) + "\n";
                batchLog.logged(title, request, prefix + summary
                        + " (" + ms(started) + " ms)");
            }
            @Override public void failed(String error) {
                String suffix = details.isEmpty() ? "" : "\n" + String.join("\n", details);
                batchLog.failed(title, request, error + suffix
                        + " (" + ms(started) + " ms)");
            }
        };
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    // The request as issued, with readable (decoded) pipes — for the query log.
    private static String entitiesUrl(List<String> qids, boolean withClaims) {
        return WIKIDATA_API + "?action=wbgetentities&ids=" + String.join("|", qids)
                + "&props=" + (withClaims ? "labels|claims" : "labels")
                + "&languages=en|mul&format=json";
    }

    private static final int GET_ENTITIES_CONCURRENCY = 6;

    /** One physical batch, with the transport's own short retry. Overridable so a test
     *  can fail a single batch and exercise the real fan-out around it — there is no
     *  other seam below the HTTP call. */
    protected JsonNode getEntitiesBatchWithRetry(
            List<String> qids, boolean withClaims) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return getEntitiesBatch(qids, withClaims);
            } catch (Exception e) {
                last = e;
                if (Thread.currentThread().isInterrupted()) throw e;
                if (!worthRetryingUnchanged(e)) {
                    throw e;
                }
                if (attempt == MAX_ATTEMPTS) break;
                try {
                    Thread.sleep(retryWaitMillis(e, attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
        throw last;
    }

    /**
     * Whether issuing the SAME request again can plausibly do better.
     *
     * <p>A status the server will not change its mind about (404, 400) is not worth five
     * attempts. Neither is a TIMEOUT: it says this batch is too heavy, and asking for the
     * same 50 entities four more times only spends ~15s of backoff learning that again —
     * it is the executor's split, not another identical attempt, that makes progress.
     */
    static boolean worthRetryingUnchanged(Exception error) {
        if (error instanceof ApiHttpException http) {
            return RetryAfter.isRetryableStatus(http.status());
        }
        return !(error instanceof batch.ResponseTimeoutException);
    }

    /**
     * How long to wait before the next attempt: what the server asked for, else an
     * exponential 1s, 2s, 4s, 8s.
     *
     * <p>The old fixed 500ms×attempt spent its whole budget inside three seconds. A
     * Wikimedia throttling window outlasts that comfortably — the one that cost 54
     * batches ran across dozens of requests — so the backoff has to be able to sit
     * out a window, not just a hiccup.
     */
    static long retryWaitMillis(Exception error, int attempt) {
        if (error instanceof ApiHttpException http && http.retryAfterMillis() > 0) {
            return http.retryAfterMillis();
        }
        return 1000L << Math.min(attempt - 1, 3);
    }

    /** Enough attempts, with the backoff above, to sit out a throttling window
     *  (~15s of waiting) rather than only a momentary blip. */
    private static final int MAX_ATTEMPTS = 5;

    private JsonNode getEntitiesBatch(
            List<String> qids, boolean withClaims) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("action",    "wbgetentities");
        params.put("ids",       String.join("%7C", qids));
        params.put("props",     withClaims ? "labels%7Cclaims" : "labels");
        // en + mul (the language-agnostic label) so an item without an English label
        // still resolves to a name instead of staying a QID — matches the SPARQL
        // SERVICE "en,mul".
        params.put("languages", "en%7Cmul");
        params.put("format",    "json");
        return get(WIKIDATA_API, params);
    }

    // Visible for WbGetEntitiesParseTest. Returns the number of entities parsed from
    // THIS response (accurate under concurrency — the shared map's size can't be used
    // as a per-batch count when batches run in parallel).
    static int parseEntities(
            JsonNode root, List<String> claimPids, Map<String, ApiEntity> out) {
        int[] parsed = {0};
        root.path("entities").fields().forEachRemaining(entry -> {
            JsonNode entity = entry.getValue();
            String qid = entity.path("id").asText(entry.getKey());
            if (!WikidataIds.isQid(qid)) return;

            if (entity.has("missing")) {
                out.put(qid, new ApiEntity(qid, "", Map.of(), true));
                parsed[0]++;
                return;
            }

            String label = entity.path("labels").path("en").path("value").asText("");
            if (label.isBlank()) {
                label = entity.path("labels").path("mul").path("value").asText("");
            }

            Map<String, List<String>> claims = new LinkedHashMap<>();
            Map<String, String> valueless = new LinkedHashMap<>();
            for (String pid : claimPids) {
                com.fasterxml.jackson.databind.JsonNode statements =
                        entity.path("claims").path(pid);
                List<String> vals = entityQids(statements);
                if (!vals.isEmpty()) {
                    claims.put(pid, vals);
                    continue;
                }
                // Statements exist but none carries a value: the source is SAYING
                // something (unknown / none), not staying silent. Recorded so a caller
                // can tell that apart from a property with no statement at all.
                String snakType = onlySnakType(statements);
                if (snakType != null) valueless.put(pid, snakType);
            }
            out.put(qid, new ApiEntity(qid, label, claims, false, valueless));
            parsed[0]++;
        });
        return parsed[0];
    }

    /**
     * The snak type shared by every non-deprecated statement of a property that produced
     * no value — {@code somevalue} or {@code novalue} — or null when there are no such
     * statements (or they disagree, which is not a claim about absence).
     */
    private static String onlySnakType(JsonNode claimsArray) {
        if (!claimsArray.isArray() || claimsArray.isEmpty()) return null;
        String seen = null;
        for (JsonNode claim : claimsArray) {
            if ("deprecated".equals(claim.path("rank").asText())) continue;
            String snakType = claim.path("mainsnak").path("snaktype").asText("");
            if (!"somevalue".equals(snakType) && !"novalue".equals(snakType)) return null;
            if (seen != null && !seen.equals(snakType)) return null;
            seen = snakType;
        }
        return seen;
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
            if (WikidataIds.isQid(id)) out.add(id);
        }
        return out;
    }

    /**
     * One statement of a claim: the mainsnak value (entity QID or literal) and, per
     * requested qualifier PID, the qualifier snaks' raw values. {@code id} is the
     * statement GUID (its reified identity).
     */
    public record ApiStatement(
            String id,
            String value,
            Map<String, List<String>> qualifiers) {

        /** The raw values of a qualifier PID (empty if absent). */
        public List<String> qualifier(String pid) {
            return qualifiers.getOrDefault(pid, List.of());
        }
    }

    // Visible for WbGetEntitiesParseTest. Returns the number of entities that had at
    // least one non-deprecated statement for statementPid.
    static int parseStatements(
            JsonNode root, String statementPid, List<String> qualifierPids,
            Map<String, List<ApiStatement>> out) {
        int[] n = {0};
        root.path("entities").fields().forEachRemaining(entry -> {
            JsonNode entity = entry.getValue();
            if (entity.has("missing")) return;
            String qid = entity.path("id").asText("");
            if (!WikidataIds.isQid(qid)) return;

            JsonNode claims = entity.path("claims").path(statementPid);
            if (!claims.isArray() || claims.isEmpty()) return;

            List<ApiStatement> stmts = new ArrayList<>();
            for (JsonNode claim : claims) {
                if ("deprecated".equals(claim.path("rank").asText())) continue;
                String value = snakValue(claim.path("mainsnak").path("datavalue"));
                if (value == null) continue;

                Map<String, List<String>> quals = new LinkedHashMap<>();
                for (String pq : qualifierPids) {
                    JsonNode snaks = claim.path("qualifiers").path(pq);
                    if (!snaks.isArray()) continue;
                    List<String> vals = new ArrayList<>();
                    for (JsonNode snak : snaks) {
                        String v = snakValue(snak.path("datavalue"));
                        if (v != null) vals.add(v);
                    }
                    if (!vals.isEmpty()) quals.put(pq, vals);
                }
                stmts.add(new ApiStatement(
                        claim.path("id").asText(""), value, quals));
            }
            if (!stmts.isEmpty()) {
                out.put(qid, stmts);
                n[0]++;
            }
        });
        return n[0];
    }

    /** A snak's raw value by datatype: entity → {@code Qxxx}, time → the ISO time
     *  string, monolingualtext → text, quantity → the (unsigned) amount, string →
     *  the string. Null when the snak has no value (novalue/somevalue). */
    private static String snakValue(JsonNode datavalue) {
        if (datavalue.isMissingNode()) return null;
        JsonNode val = datavalue.path("value");
        return switch (datavalue.path("type").asText()) {
            case "wikibase-entityid" -> {
                String id = val.path("id").asText("");
                if (id.isBlank() && val.has("numeric-id")) {
                    id = "Q" + val.path("numeric-id").asText();
                }
                yield id.isBlank() ? null : id;
            }
            case "time"            -> val.path("time").asText(null);
            case "monolingualtext" -> val.path("text").asText(null);
            case "quantity" -> {
                String a = val.path("amount").asText("");
                yield a.isBlank() ? null : (a.startsWith("+") ? a.substring(1) : a);
            }
            case "string"          -> val.asText(null);
            default -> val.isTextual() ? val.asText() : null;
        };
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

        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Accept",     "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);

        int responseStatus = -1;
        try {
            // Establish the response boundary explicitly. A timeout here is a
            // connection/availability failure. Once this returns, a timeout while
            // consuming the stream is specifically a response-body timeout.
            responseStatus = conn.getResponseCode();
            try (var stream = conn.getInputStream()) {
                JsonNode result = mapper.readTree(stream);

                log.accept("[API " + id + "] OK timeMs="
                                   + (System.nanoTime() - started) / 1_000_000
                                   + "\n");

                return result;
            }
        } catch (IOException e) {
            log.accept("[API " + id + "] ERROR "
                               + e.getMessage()
                               + " timeMs="
                               + (System.nanoTime() - started) / 1_000_000
                               + "\n");

            throw transportFailure(e, responseStatus,
                    RetryAfter.millis(conn.getHeaderField("Retry-After"), -1), url);
        }
    }

    /**
     * The failure to raise for a request that did not produce a body — the decision that
     * tells the batch executor what to do next, so it is made in one place.
     *
     * <p>A read timeout is NOT an outcome the server reported: the headers arrived, so
     * the connection still answers 200, and the BODY ran out of time. Reading a status
     * here and wrapping it as an {@link ApiHttpException} handed the classifier a status
     * it has no rule for, which is FATAL — so a 50-entity batch that merely needed
     * splitting refused the whole run. A timeout stays a timeout, because that is the
     * failure that means "ask for less".
     */
    static IOException transportFailure(
            IOException error, int status, long retryAfterMillis, String url) {
        String message = error.getMessage() + " | URL: " + url;

        if (error instanceof java.net.SocketTimeoutException && status > 0) {
            return new batch.ResponseTimeoutException(message, error);
        }
        if (error instanceof java.net.SocketTimeoutException) {
            java.net.SocketTimeoutException timeout = new java.net.SocketTimeoutException(message);
            timeout.initCause(error);
            return timeout; // no response: retry the connection, do not split the batch
        }
        // A refused request carries the two facts that decide what to do next: the
        // status, and how long the server asked us to wait. Collapsing them into a
        // bare IOException is what left the retry unable to tell throttling from a
        // permanent error, so it waited half a second and gave up on a 429.
        if (status > 0) {
            return new ApiHttpException(status, retryAfterMillis, message, error);
        }
        return new IOException(message, error);
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
