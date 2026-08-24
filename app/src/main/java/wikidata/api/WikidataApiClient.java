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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

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

    /**
     * What an entity request asks for beyond claims, for the call in progress.
     *
     * <p>Ambient rather than a parameter because {@link #getEntitiesBatchWithRetry} is
     * a {@code protected} seam five test doubles override; giving it a projection
     * argument would break every one. It is read on the CALLING thread and handed to
     * the work units as a value, so a batch running on a pool thread carries the
     * projection its caller chose — the thread-local never has to be visible where the
     * request is actually made.
     *
     * <p>The default is LABEL alone, which is what the callers that do not state a
     * projection need: QualifierLoader's two fetches, SelectionContentResolver and
     * ValueVocabularyDiscovery all read {@code label()} and nothing else. It fails
     * toward LESS data, so a new caller wanting aliases gets none and no error —
     * state the projection rather than adding to this list.
     */
    private final ThreadLocal<Set<FactDemand.EntityMetadata>> entityProjection =
            ThreadLocal.withInitial(() -> Set.of(FactDemand.EntityMetadata.LABEL));

    public static final String DEFAULT_USER_AGENT =
            "QuizProject/1.0 (ggyepesi@gmail.com)";

    private static final String WIKIDATA_API =
            "https://www.wikidata.org/w/api.php";
    private static final String WIKIPEDIA_API =
            "https://en.wikipedia.org/w/api.php";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String userAgent;
    private work.CancellationToken cancellation = new work.CancellationToken();
    private WikidataFactStore facts = new WikidataFactStore();
    private int entityConcurrency = 6;
    private final Map<String, List<String>> aliasCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private java.util.function.Consumer<String> log = s -> {};
    private final java.util.concurrent.atomic.AtomicLong requestSeq =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong aliasRequests = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong standaloneAliasRequests = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong aliasFailures = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong aliasEntities = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong aliasResponseBytes = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong aliasTransferredBytes = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong aliasValueBytes = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong aliasElapsedMillis = new java.util.concurrent.atomic.AtomicLong();

    /** Physical cost of every response carrying aliases, distinguishing aliases that
     * ride an entity request from the literal-only fallback pass. Response bytes are
     * the complete JSON response; value bytes isolate aliases and approximate their
     * marginal payload. Elapsed time is summed request time, so concurrent requests
     * may exceed wall-clock time. Failures count the STANDALONE pass only: a request
     * that would have been made anyway does not fail on account of the aliases it
     * happened to carry. */
    public record AliasMetrics(long requests, long standaloneRequests,
                               long failures, long entities,
                               long responseBytes, long transferredBytes,
                               long valueBytes,
                               long elapsedMillis) { }

    public AliasMetrics aliasMetrics() {
        return new AliasMetrics(aliasRequests.get(), standaloneAliasRequests.get(),
                aliasFailures.get(),
                aliasEntities.get(), aliasResponseBytes.get(),
                aliasTransferredBytes.get(), aliasValueBytes.get(),
                aliasElapsedMillis.get());
    }


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

    public WikidataApiClient cancellation(work.CancellationToken token) {
        cancellation = token == null ? new work.CancellationToken() : token;
        return this;
    }

    public WikidataApiClient facts(WikidataFactStore store) {
        facts = store == null ? new WikidataFactStore() : store;
        return this;
    }

    public WikidataFactStore facts() { return facts; }
    /** The concurrency this run was told to use, so a second acquisition path can obey
     *  the same policy instead of choosing its own. */
    public int entityConcurrency() { return entityConcurrency; }

    public WikidataApiClient entityConcurrency(int value) {
        entityConcurrency = Math.max(1, Math.min(16, value));
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
    // wbgetentities by QID — labels (+ selected claim PIDs as decoded values)
    // ------------------------------------------------------------------

    /**
     * One entity as returned by {@link #getEntities}: its English label (blank if
     * absent) and, for each requested claim PID, decoded values in document order
     * (deprecated-rank statements skipped). {@link #entityQids} retains the narrower
     * entity-QID-only view for existing referent consumers.
     */
    public record ApiEntity(
            String qid,
            String label,
            Map<String, List<String>> claimEntityQids,
            boolean missing,
            Map<String, String> valuelessClaims,
            List<String> aliases,
            boolean aliasesAnswered,
            Map<String, List<String>> claimValues,
            String enwikiTitle) {

        public ApiEntity(
                String qid,
                String label,
                Map<String, List<String>> claimEntityQids) {
            this(qid, label, claimEntityQids, false, Map.of(), List.of(), false,
                    claimEntityQids, "");
        }

        public ApiEntity(
                String qid,
                String label,
                Map<String, List<String>> claimEntityQids,
                boolean missing) {
            this(qid, label, claimEntityQids, missing, Map.of(), List.of(), false,
                    claimEntityQids, "");
        }

        public ApiEntity(
                String qid, String label, Map<String, List<String>> claimEntityQids,
                boolean missing, Map<String, String> valuelessClaims) {
            this(qid, label, claimEntityQids, missing, valuelessClaims, List.of(), false,
                    claimEntityQids, "");
        }

        /** For a caller that HAS asked about aliases; an empty list then means the
         *  entity has none, which is an answer. */
        public ApiEntity(
                String qid, String label, Map<String, List<String>> claimEntityQids,
                boolean missing, Map<String, String> valuelessClaims,
                List<String> aliases) {
            this(qid, label, claimEntityQids, missing, valuelessClaims, aliases, true,
                    claimEntityQids, "");
        }

        public ApiEntity {
            valuelessClaims = valuelessClaims == null ? Map.of()
                    : Map.copyOf(valuelessClaims);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            claimValues = claimValues == null ? Map.of() : Map.copyOf(claimValues);
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
        public List<String> entityQids(String pid) {
            return claimEntityQids.getOrDefault(pid, List.of());
        }

        /** Every decoded mainsnak value for a requested PID. Unlike
         * {@link #entityQids},
         * this includes literals (dates, strings and media names) as well as QIDs. */
        public List<String> values(String pid) {
            return claimValues.getOrDefault(pid, List.of());
        }
    }

    /**
     * Resolves entities by QID through the {@code wbgetentities} action API, in
     * batches of 50 (the API limit). Always returns English labels; when
     * {@code claimPids} is non-empty, {@code props=claims} is requested and each
     * listed PID's entity and literal values are extracted. This is the reliable,
     * non-SPARQL path for labels + outgoing claims (no query-engine timeout, no
     * full-index scan) — see docs/extraction-batched-membership.md, slice 3.
     */
    /** Per-batch log hook so a caller (e.g. the generation query log) can show each
     *  wbgetentities request as a structured entry. All three args are the request
     *  title, the request URL, and a completion summary ("N entities (ms)"/"FAILED…").
     *  Attempts fan out, while completed results are reported by the executor's
     *  coordinating thread in stable commit order. */
    @FunctionalInterface
    public interface BatchLog {
        interface Running {
            default void detail(String text) { }
            void done(String summary);
            void failed(String error);
        }

        void logged(String title, String request, String summary);

        default void message(String text) { }

        /** One rendering rule for retry/adaptation detail followed by its outcome. */
        static String withDetails(List<String> details, String terminal) {
            return details == null || details.isEmpty() ? terminal
                    : String.join("\n", details) + "\n" + terminal;
        }

        /**
         * Opens the visible entry before the request is issued. The default preserves
         * compatibility with simple completion-only consumers while live workflow logs
         * can override it to keep a slow batch visibly RUNNING.
         */
        default Running started(String title, String request) {
            return new Running() {
                private final List<String> details = new ArrayList<>();
                @Override public void detail(String text) { details.add(text); }
                @Override public void done(String summary) {
                    logged(title, request, BatchLog.withDetails(details, summary));
                }
                @Override public void failed(String error) {
                    BatchLog.this.failed(title, request,
                            BatchLog.withDetails(details, error));
                }
            };
        }

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
        Set<FactDemand.EntityMetadata> projection = entityProjection.get();
        List<WorkUnit<Map<String, ApiEntity>>> roots = entityUnits(qids, pids, projection);
        entityExecutor(batchLog).run(roots,
                (descriptor, entities) -> out.putAll(entities));
        return out;
    }

    public Map<String, ApiEntity> getEntities(
            List<String> qids, List<String> claimPids,
            Collection<FactDemand.EntityMetadata> metadata, BatchLog batchLog)
            throws Exception {
        return withEntityProjection(metadata, () -> getEntities(qids, claimPids, batchLog));
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
        Set<FactDemand.EntityMetadata> projection = entityProjection.get();
        List<WorkDescriptor> failed = entityExecutor(batchLog).runBestEffort(
                entityUnits(qids, pids, projection),
                (descriptor, entities) -> out.putAll(entities));
        return new PartialEntities(out, failed.size(), unavailableQids(failed));
    }

    public PartialEntities getEntitiesBestEffort(
            List<String> qids, List<String> claimPids,
            Collection<FactDemand.EntityMetadata> metadata, BatchLog batchLog)
            throws Exception {
        return withEntityProjection(metadata,
                () -> getEntitiesBestEffort(qids, claimPids, batchLog));
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
        Set<FactDemand.EntityMetadata> projection = entityProjection.get();
        List<WorkUnit<Map<String, ApiEntity>>> roots = entityUnits(qids, pids, projection);
        BatchExecutor<Map<String, ApiEntity>> executor = entityExecutor(batchLog);
        List<WorkDescriptor> failed = executor.runBestEffort(roots,
                (descriptor, entities) -> out.putAll(entities));
        return new PartialEntities(out, failed.size(), unavailableQids(failed));
    }

    public PartialEntities getEntityClaimsPartial(
            List<String> qids, List<String> claimPids,
            Collection<FactDemand.EntityMetadata> metadata, BatchLog batchLog)
            throws Exception {
        return withEntityProjection(metadata,
                () -> getEntityClaimsPartial(qids, claimPids, batchLog));
    }

    private <T> T withEntityProjection(Collection<FactDemand.EntityMetadata> metadata,
            java.util.concurrent.Callable<T> action) throws Exception {
        Set<FactDemand.EntityMetadata> previous = entityProjection.get();
        entityProjection.set(metadata == null ? Set.of() : Set.copyOf(metadata));
        try {
            return action.call();
        } finally {
            entityProjection.set(previous);
        }
    }

    private List<WorkUnit<Map<String, ApiEntity>>> entityUnits(
            List<String> qids, List<String> pids,
            Set<FactDemand.EntityMetadata> metadata) {
        List<String> clean = qids.stream()
                .filter(q -> q != null && WikidataIds.isQid(q)).distinct().toList();
        List<WorkUnit<Map<String, ApiEntity>>> roots = new ArrayList<>();
        for (int i = 0; i < clean.size(); i += 50) {
            roots.add(entityUnit(List.copyOf(clean.subList(i,
                    Math.min(i + 50, clean.size()))), pids, metadata));
        }
        return roots;
    }

    /** Fallback for entities that have not already answered aliases while serving an
     * ordinary labels/claims request — notably literal-only referent classes. */
    public Map<String, List<String>> getAliases(
            List<String> qids, BatchLog batchLog) throws Exception {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (qids == null) return out;
        List<String> clean = qids.stream().filter(WikidataIds::isQid).distinct().toList();

        List<String> notParsed = new ArrayList<>();
        for (String qid : clean) {
            if (aliasCache.containsKey(qid)) out.put(qid, aliasCache.get(qid));
            else notParsed.add(qid);
        }

        // Statement/literal acquisition also receives labels and aliases. Reuse that
        // retained entity document before consulting the small parsed-value cache; this
        // is what lets metadata demands ride a claims request instead of paying for a
        // later props=aliases pass.
        JsonNode held = facts.merged(null, notParsed, false, List.of(), mapper);
        Map<String, ApiEntity> heldEntities = new LinkedHashMap<>();
        parseEntities(held, List.of(), heldEntities);
        int reused = 0;
        for (String qid : notParsed) {
            ApiEntity entity = heldEntities.get(qid);
            if (entity != null && entity.aliasesAnswered()) {
                aliasCache.put(qid, entity.aliases());
                out.put(qid, entity.aliases());
                reused++;
            }
        }
        facts.recordHits(reused);

        List<String> missing = new ArrayList<>();
        for (String qid : clean) {
            if (out.containsKey(qid)) {
                continue;
            } else {
                missing.add(qid);
            }
        }
        List<WorkUnit<Map<String, List<String>>>> roots = new ArrayList<>();
        for (int i = 0; i < missing.size(); i += 50) {
            roots.add(aliasUnit(List.copyOf(missing.subList(
                    i, Math.min(i + 50, missing.size())))));
        }
        new BatchExecutor<Map<String, List<String>>>(
                BatchPolicy.defaults().withResume(false), batchProgress(batchLog),
                WikidataBatchFailureClassifier.INSTANCE, cancellation,
                batch.BatchCheckpointStore.NONE, entityConcurrency)
                .run(roots, (descriptor, aliases) -> {
                    aliases.forEach((qid, values) -> {
                        aliasCache.put(qid, values);
                    });
                    out.putAll(aliases);
                });
        return out;
    }

    private WorkUnit<Map<String, List<String>>> aliasUnit(List<String> qids) {
        return new WorkUnit<>() {
            @Override public WorkDescriptor descriptor() {
                String ids = String.join(",", qids);
                return new WorkDescriptor("wbgetentities-aliases", "aliases:" + ids,
                        "wbgetentities aliases " + qids.size() + " entities",
                        Map.of("ids", ids));
            }
            @Override public String request() { return aliasesUrl(qids); }
            @Override public Map<String, List<String>> execute() throws Exception {
                JsonNode response = getAliasesBatchWithRetry(qids);
                Map<String, ApiEntity> parsed = new LinkedHashMap<>();
                parseEntities(response, List.of(), parsed);
                Map<String, List<String>> result = new LinkedHashMap<>();
                for (String qid : qids) {
                    ApiEntity entity = parsed.get(qid);
                    result.put(qid, entity == null ? List.of() : entity.aliases());
                }
                return result;
            }
            @Override public List<? extends WorkUnit<Map<String, List<String>>>> split() {
                if (qids.size() < 2) return List.of();
                int middle = qids.size() / 2;
                return List.of(aliasUnit(qids.subList(0, middle)),
                        aliasUnit(qids.subList(middle, qids.size())));
            }
        };
    }

    private BatchExecutor<Map<String, ApiEntity>> entityExecutor(BatchLog batchLog) {
        return new BatchExecutor<>(BatchPolicy.defaults().withResume(false),
                batchProgress(batchLog), WikidataBatchFailureClassifier.INSTANCE,
                cancellation, batch.BatchCheckpointStore.NONE, entityConcurrency);
    }

    private WorkUnit<Map<String, ApiEntity>> entityUnit(
            List<String> qids, List<String> pids,
            Set<FactDemand.EntityMetadata> metadata) {
        boolean withClaims = !pids.isEmpty();
        return new WorkUnit<>() {
            @Override public WorkDescriptor descriptor() {
                String ids = String.join(",", qids);
                String mode = withClaims ? "claims" : "labels";
                return new WorkDescriptor("wbgetentities-" + mode, mode + ":" + ids,
                        "wbgetentities " + qids.size() + " entities",
                        Map.of("ids", ids, "claims", String.join(",", pids)));
            }
            @Override public String request() { return entitiesUrl(qids, withClaims, metadata); }
            @Override public Map<String, ApiEntity> execute() throws Exception {
                Map<String, ApiEntity> result = new LinkedHashMap<>();
                parseEntities(getEntitiesBatchWithRetry(qids, withClaims, pids, metadata), pids, result);
                return result;
            }
            @Override public List<? extends WorkUnit<Map<String, ApiEntity>>> split() {
                if (qids.size() < 2) return List.of();
                int middle = qids.size() / 2;
                return List.of(entityUnit(qids.subList(0, middle), pids, metadata),
                        entityUnit(qids.subList(middle, qids.size()), pids, metadata));
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
                entityConcurrency);
        executor.run(roots, (descriptor, statements) -> out.putAll(statements));
        return out;
    }

    /**
     * Fetch several main-statement properties from one claims document per entity.
     * {@code wbgetentities&props=claims} returns every property regardless of which
     * PID the caller intends to parse, so issuing one request per literal field only
     * downloads the same response repeatedly.
     *
     * @return property PID -> entity QID -> statements for that property
     */
    public Map<String, Map<String, List<ApiStatement>>> getStatementsByProperty(
            List<String> entityQids, List<String> statementPids,
            BatchLog batchLog) throws Exception {
        Map<String, Map<String, List<ApiStatement>>> out = new LinkedHashMap<>();
        if (entityQids == null || statementPids == null) return out;
        List<String> pids = statementPids.stream()
                .filter(WikidataIds::isPid).distinct().toList();
        if (pids.isEmpty()) return out;
        List<String> clean = entityQids.stream()
                .filter(WikidataIds::isQid).distinct().toList();
        List<WorkUnit<Map<String, Map<String, List<ApiStatement>>>>> roots =
                new ArrayList<>();
        for (int i = 0; i < clean.size(); i += 50) {
            roots.add(statementGroupUnit(List.copyOf(clean.subList(i,
                    Math.min(i + 50, clean.size()))), pids));
        }
        BatchExecutor<Map<String, Map<String, List<ApiStatement>>>> executor =
                new BatchExecutor<>(BatchPolicy.defaults().withResume(false),
                        batchProgress(batchLog), WikidataBatchFailureClassifier.INSTANCE,
                        cancellation, batch.BatchCheckpointStore.NONE,
                        entityConcurrency);
        executor.run(roots, (descriptor, statements) -> statements.forEach((pid, byQid) ->
                out.computeIfAbsent(pid, ignored -> new LinkedHashMap<>()).putAll(byQid)));
        return out;
    }

    /** What a grouped statement lookup came back with, and which entities it could not
     *  reach. Same contract as {@link PartialEntities}: absence in {@code statements} is
     *  only "this entity has no such statement" for a QID that is NOT unavailable. */
    public record PartialStatements(
            Map<String, Map<String, List<ApiStatement>>> statements,
            int failedBatches,
            List<String> unavailableQids) {
        public PartialStatements {
            unavailableQids = unavailableQids == null
                    ? List.of() : List.copyOf(unavailableQids);
        }
    }

    /**
     * Like {@link #getStatementsByProperty}, but keeps what the reachable batches
     * returned instead of discarding a whole load because some of it failed.
     *
     * <p>A caller loading a declared field over thousands of entities has no use for
     * all-or-nothing: one unreachable batch of 50 would throw away the answers for the
     * other thousands, report them all as unresolved, and make the next run re-ask for
     * every one of them. It must, though, still know WHICH entities went unanswered —
     * absence is otherwise indistinguishable from "the property is not there".
     */
    public PartialStatements getStatementsByPropertyPartial(
            List<String> entityQids, List<String> statementPids,
            BatchLog batchLog) throws Exception {
        Map<String, Map<String, List<ApiStatement>>> out = new LinkedHashMap<>();
        if (entityQids == null || statementPids == null) {
            return new PartialStatements(out, 0, List.of());
        }
        List<String> pids = statementPids.stream()
                .filter(WikidataIds::isPid).distinct().toList();
        if (pids.isEmpty()) return new PartialStatements(out, 0, List.of());
        List<String> clean = entityQids.stream()
                .filter(WikidataIds::isQid).distinct().toList();
        List<WorkUnit<Map<String, Map<String, List<ApiStatement>>>>> roots =
                new ArrayList<>();
        for (int i = 0; i < clean.size(); i += 50) {
            roots.add(statementGroupUnit(List.copyOf(clean.subList(i,
                    Math.min(i + 50, clean.size()))), pids));
        }
        BatchExecutor<Map<String, Map<String, List<ApiStatement>>>> executor =
                new BatchExecutor<>(BatchPolicy.defaults().withResume(false),
                        batchProgress(batchLog), WikidataBatchFailureClassifier.INSTANCE,
                        cancellation, batch.BatchCheckpointStore.NONE,
                        entityConcurrency);
        List<WorkDescriptor> failed = executor.runBestEffort(roots,
                (descriptor, statements) -> statements.forEach((pid, byQid) ->
                        out.computeIfAbsent(pid, ignored -> new LinkedHashMap<>())
                                .putAll(byQid)));
        return new PartialStatements(out, failed.size(), unavailableQids(failed));
    }

    private WorkUnit<Map<String, Map<String, List<ApiStatement>>>> statementGroupUnit(
            List<String> qids, List<String> statementPids) {
        return new WorkUnit<>() {
            @Override public WorkDescriptor descriptor() {
                String ids = String.join(",", qids);
                return new WorkDescriptor("wbgetentities-statements",
                        "statements:" + String.join(",", statementPids) + ":" + ids,
                        "wbgetentities " + qids.size() + " entities",
                        Map.of("ids", ids,
                                "statements", String.join(",", statementPids)));
            }
            @Override public String request() { return entitiesUrl(qids, true, Set.of()); }
            @Override public Map<String, Map<String, List<ApiStatement>>> execute()
                    throws Exception {
                JsonNode root = getEntitiesBatchWithRetry(qids, true, statementPids,
                        Set.of());
                Map<String, Map<String, List<ApiStatement>>> result = new LinkedHashMap<>();
                for (String pid : statementPids) {
                    Map<String, List<ApiStatement>> byQid = new LinkedHashMap<>();
                    parseStatements(root, pid, List.of(), byQid);
                    result.put(pid, byQid);
                }
                return result;
            }
            @Override public List<? extends WorkUnit<Map<String, Map<String,
                    List<ApiStatement>>>>> split() {
                if (qids.size() < 2) return List.of();
                int middle = qids.size() / 2;
                return List.of(statementGroupUnit(qids.subList(0, middle), statementPids),
                        statementGroupUnit(qids.subList(middle, qids.size()), statementPids));
            }
        };
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
            @Override public String request() { return entitiesUrl(qids, true, Set.of()); }
            @Override public Map<String, List<ApiStatement>> execute() throws Exception {
                Map<String, List<ApiStatement>> result = new LinkedHashMap<>();
                parseStatements(getEntitiesBatchWithRetry(qids, true,
                        List.of(statementPid), Set.of()), statementPid,
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
        return new BatchProgress() {
            @Override public Running started(String title, String request) {
                BatchLog.Running running = batchLog.started(title, request);
                return new Running() {
                    private final long started = System.nanoTime();
                    @Override public void detail(String text) { running.detail(text); }
                    @Override public void done(String summary) {
                        running.done(summary + " (" + ms(started) + " ms)");
                    }
                    @Override public void failed(String error) {
                        running.failed(error + " (" + ms(started) + " ms)");
                    }
                };
            }

            @Override public void message(String text) { batchLog.message(text); }
        };
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    // The request as issued, with readable (decoded) pipes — for the query log.
    private static String entitiesUrl(List<String> qids, boolean withClaims) {
        return entitiesUrl(qids, withClaims, FactDemand.allMetadata());
    }

    private static String entitiesUrl(List<String> qids, boolean withClaims,
            Collection<FactDemand.EntityMetadata> metadata) {
        return WIKIDATA_API + "?action=wbgetentities&ids=" + String.join("|", qids)
                + "&props=" + entityProps(withClaims, metadata)
                + (metadata != null && metadata.contains(FactDemand.EntityMetadata.SITELINKS)
                        ? "&sitefilter=" + SITE_FILTER : "")
                + "&languages=en|mul&format=json";
    }

    private static String aliasesUrl(List<String> qids) {
        return WIKIDATA_API + "?action=wbgetentities&ids=" + String.join("|", qids)
                + "&props=aliases&languages=en|mul&format=json";
    }

    /** Compatibility projection. Plan-aware callers use the overload below so entity
     * metadata is acquired by declared demand rather than riding every claims body. */
    static String entityProps(boolean withClaims) {
        return entityProps(withClaims, FactDemand.allMetadata());
    }

    static String entityProps(boolean withClaims,
            Collection<FactDemand.EntityMetadata> metadata) {
        List<String> props = new ArrayList<>();
        if (metadata != null && metadata.contains(FactDemand.EntityMetadata.LABEL))
            props.add("labels");
        if (withClaims) props.add("claims");
        if (metadata != null && metadata.contains(FactDemand.EntityMetadata.ALIASES))
            props.add("aliases");
        if (metadata != null && metadata.contains(FactDemand.EntityMetadata.SITELINKS))
            props.add("sitelinks");
        return String.join("|", props);
    }

    /** Only the English article: an entity's full sitelink set is one line per language
     *  and nobody here reads the other three hundred. */
    private static final String SITE_FILTER = "enwiki";

    private static String encodePipes(String value) {
        return value.replace("|", "%7C");
    }

    /** One physical batch, with the transport's own short retry. Overridable so a test
     *  can fail a single batch and exercise the real fan-out around it — there is no
     *  other seam below the HTTP call. */
    protected JsonNode getEntitiesBatchWithRetry(
            List<String> qids, boolean withClaims) throws Exception {
        return getEntitiesBatchWithRetry(qids, withClaims, null);
    }

    /**
     * @param pids the claim properties this batch is FOR. The fact store retains only
     *             these, so the run holds the declared slice of an entity rather than
     *             its whole claims body — and refuses to answer for any property it did
     *             not retain, since a slice cannot tell "no such statement" from "never
     *             fetched". Null asks for, and is only satisfied by, the whole body.
     */
    protected JsonNode getEntitiesBatchWithRetry(
            List<String> qids, boolean withClaims, List<String> pids) throws Exception {
        return getEntitiesBatchWithRetryProjected(qids, withClaims, pids,
                entityProjection.get());
    }

    protected JsonNode getEntitiesBatchWithRetry(
            List<String> qids, boolean withClaims, List<String> pids,
            Collection<FactDemand.EntityMetadata> metadata) throws Exception {
        return withEntityProjection(metadata,
                () -> getEntitiesBatchWithRetry(qids, withClaims, pids));
    }

    private JsonNode getEntitiesBatchWithRetryProjected(
            List<String> qids, boolean withClaims, List<String> pids,
            Collection<FactDemand.EntityMetadata> metadata) throws Exception {
        Set<FactDemand.EntityMetadata> projection = metadata == null
                ? Set.of() : Set.copyOf(metadata);
        JsonNode cached = facts.response(qids, withClaims, pids, projection, mapper);
        if (cached != null) {
            facts.recordHits(qids == null ? 0 : qids.size(), pids);
            return cached;
        }
        List<String> missing = facts.missing(qids, withClaims, pids, projection);
        facts.recordHits((qids == null ? 0 : qids.size()) - missing.size(), pids);
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Set<FactDemand.EntityMetadata> previousProjection = entityProjection.get();
                JsonNode fetched;
                entityProjection.set(projection);
                try {
                    // Keep the established two-argument override as the transport seam:
                    // adaptive-batch tests and alternate transports override it. The
                    // per-worker projection is read by the default implementation.
                    fetched = getEntitiesBatch(missing, withClaims);
                } finally {
                    entityProjection.set(previousProjection);
                }
                // The answer is what was true when it was asked for: the entities this
                // fetch carried, plus the ones the store already held. Assemble it
                // BEFORE retaining anything, because retaining enforces the budget and
                // may evict the very documents the cached half is made of — asking the
                // store again afterwards would hand back whatever eviction had left.
                JsonNode answer = facts.merged(fetched, qids, withClaims, pids,
                        projection, mapper);
                facts.accept(fetched, withClaims, pids, projection);
                return answer;
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
     * attempts. Neither is a body TIMEOUT/interruption: the batch executor owns their
     * bounded retry/split policy, so a hidden transport retry loop must not multiply it.
     */
    static boolean worthRetryingUnchanged(Exception error) {
        if (error instanceof ApiHttpException http) {
            return RetryAfter.isRetryableStatus(http.status());
        }
        return !(error instanceof batch.ResponseTimeoutException
                || error instanceof batch.ResponseInterruptedException);
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

    protected JsonNode getEntitiesBatch(
            List<String> qids, boolean withClaims) throws Exception {
        return getEntitiesBatch(qids, withClaims, entityProjection.get());
    }

    protected JsonNode getEntitiesBatch(
            List<String> qids, boolean withClaims,
            Collection<FactDemand.EntityMetadata> metadata) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("action",    "wbgetentities");
        params.put("ids",       String.join("%7C", qids));
        params.put("props",     encodePipes(entityProps(withClaims, metadata)));
        if (metadata != null && metadata.contains(FactDemand.EntityMetadata.SITELINKS))
            params.put("sitefilter", SITE_FILTER);
        // en + mul (the language-agnostic label) so an item without an English label
        // still resolves to a name instead of staying a QID — matches the SPARQL
        // SERVICE "en,mul".
        params.put("languages", "en%7Cmul");
        params.put("format",    "json");
        return get(WIKIDATA_API, params);
    }

    protected JsonNode getAliasesBatchWithRetry(List<String> qids) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("action", "wbgetentities");
                params.put("ids", String.join("%7C", qids));
                params.put("props", "aliases");
                params.put("languages", "en%7Cmul");
                params.put("format", "json");
                return get(WIKIDATA_API, params);
            } catch (Exception e) {
                last = e;
                if (Thread.currentThread().isInterrupted() || !worthRetryingUnchanged(e)) {
                    throw e;
                }
                if (attempt == MAX_ATTEMPTS) break;
                Thread.sleep(retryWaitMillis(e, attempt));
            }
        }
        throw last;
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
            boolean aliasesAnswered = entity.has("aliases");
            List<String> aliases = new ArrayList<>();
            for (String language : List.of("en", "mul")) {
                JsonNode values = entity.path("aliases").path(language);
                if (!values.isArray()) continue;
                for (JsonNode value : values) {
                    String alias = value.path("value").asText("").trim();
                    if (!alias.isBlank() && !aliases.contains(alias)) aliases.add(alias);
                }
            }

            Map<String, List<String>> claims = new LinkedHashMap<>();
            Map<String, List<String>> values = new LinkedHashMap<>();
            Map<String, String> valueless = new LinkedHashMap<>();
            for (String pid : claimPids) {
                com.fasterxml.jackson.databind.JsonNode statements =
                        entity.path("claims").path(pid);
                List<String> vals = entityQids(statements);
                if (!vals.isEmpty()) {
                    claims.put(pid, vals);
                }
                List<String> rawValues = statementValues(statements);
                if (!rawValues.isEmpty()) values.put(pid, rawValues);
                if (!rawValues.isEmpty()) continue;
                // Statements exist but none carries a value: the source is SAYING
                // something (unknown / none), not staying silent. Recorded so a caller
                // can tell that apart from a property with no statement at all.
                String snakType = onlySnakType(statements);
                if (snakType != null) valueless.put(pid, snakType);
            }
            out.put(qid, new ApiEntity(
                    qid, label, claims, false, valueless, aliases, aliasesAnswered,
                    values, entity.path("sitelinks").path("enwiki").path("title").asText("")));
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
        for (JsonNode claim : truthy(claimsArray)) {
            String snakType = claim.path("mainsnak").path("snaktype").asText("");
            if (!"somevalue".equals(snakType) && !"novalue".equals(snakType)) return null;
            if (seen != null && !seen.equals(snakType)) return null;
            seen = snakType;
        }
        return seen;
    }

    /** The entity-QID values a FIELD takes: the property's truthy statements. */
    private static List<String> entityQids(JsonNode claimsArray) {
        if (!claimsArray.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode claim : truthy(claimsArray)) {
            JsonNode val = claim.path("mainsnak").path("datavalue").path("value");
            String id = val.path("id").asText("");
            if (id.isBlank() && val.has("numeric-id")) {
                id = "Q" + val.path("numeric-id").asText();
            }
            if (WikidataIds.isQid(id)) out.add(id);
        }
        return out;
    }

    /** The values a FIELD takes: every truthy mainsnak in document order, decoded
     * with the same datatype rules used by grouped statement acquisition. */
    private static List<String> statementValues(JsonNode claimsArray) {
        if (!claimsArray.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode claim : truthy(claimsArray)) {
            String value = snakValue(claim.path("mainsnak").path("datavalue"));
            if (value != null) out.add(value);
        }
        return out;
    }

    /**
     * A property's TRUTHY statements: the preferred ones if it has any, otherwise its
     * normal ones, never the deprecated ones. This is what {@code wdt:} means in SPARQL,
     * and what a field projected from a claim has always meant — a film states a release
     * date per country and marks the first one preferred, so reading every non-deprecated
     * statement gave a single-valued field a dozen values once the field moved off the
     * SPARQL path onto this one.
     *
     * <p>Reification does NOT go through here: a statement class exists to represent
     * each statement, so it keeps every non-deprecated one, rank and all.
     */
    private static List<JsonNode> truthy(JsonNode claimsArray) {
        if (!claimsArray.isArray()) return List.of();
        List<JsonNode> preferred = new ArrayList<>();
        List<JsonNode> normal = new ArrayList<>();
        for (JsonNode claim : claimsArray) {
            String rank = claim.path("rank").asText("normal");
            if ("deprecated".equals(rank)) continue;
            if ("preferred".equals(rank)) preferred.add(claim);
            else normal.add(claim);
        }
        return preferred.isEmpty() ? normal : preferred;
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
        String requestedProps = params.getOrDefault("props", "");
        boolean aliasesRequest = requestedProps.contains("aliases");
        boolean standaloneAliases = "aliases".equals(requestedProps);
        if (aliasesRequest) {
            aliasRequests.incrementAndGet();
            if (standaloneAliases) standaloneAliasRequests.incrementAndGet();
        }

        log.accept("\n[API " + id + "] GET " + url + "\n");

        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Accept",     "application/json");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);

        int responseStatus = -1;
        try {
            // Establish the response boundary explicitly. A timeout here is a
            // connection/availability failure. Once this returns, a timeout while
            // consuming the stream is specifically a response-body timeout.
            responseStatus = conn.getResponseCode();
            // Both are resources, and in this order: GZIPInputStream reads the gzip
            // header in its constructor, so a truncated or mislabelled body throws
            // THERE. Declared second, the counting stream — and with it the
            // connection's — is still closed; built before the try, it leaked once per
            // attempt, and a body that arrives truncated is retried.
            try (CountingInputStream transferred =
                         new CountingInputStream(conn.getInputStream());
                 InputStream stream = decodedStream(
                         transferred, conn.getHeaderField("Content-Encoding"))) {
                JsonNode result = mapper.readTree(stream);

                if (aliasesRequest) {
                    aliasElapsedMillis.addAndGet(
                            (System.nanoTime() - started) / 1_000_000);
                    aliasEntities.addAndGet(result.path("entities").size());
                    aliasTransferredBytes.addAndGet(transferred.count());
                    try {
                        aliasResponseBytes.addAndGet(mapper.writeValueAsBytes(result).length);
                        long values = 0;
                        for (var entities = result.path("entities").elements();
                                entities.hasNext(); ) {
                            values += mapper.writeValueAsBytes(
                                    entities.next().path("aliases")).length;
                        }
                        aliasValueBytes.addAndGet(values);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                        // Measurement must never turn a valid response into a failure.
                    }
                }

                log.accept("[API " + id + "] OK timeMs="
                                   + (System.nanoTime() - started) / 1_000_000
                                   + "\n");

                return result;
            }
        } catch (IOException e) {
            if (aliasesRequest) {
                // A claims batch that timed out is a claims failure, reported by the
                // batch executor that will split and retry it. Counting it here made a
                // routine split read as "aliases failed" for a request that would have
                // been made with or without them. Only the standalone pass fails AS an
                // alias request; its elapsed time is still summed either way, which the
                // report labels as aggregate request time.
                if (standaloneAliases) aliasFailures.incrementAndGet();
                aliasElapsedMillis.addAndGet(
                        (System.nanoTime() - started) / 1_000_000);
            }
            log.accept("[API " + id + "] ERROR "
                               + e.getMessage()
                               + " timeMs="
                               + (System.nanoTime() - started) / 1_000_000
                               + "\n");

            throw transportFailure(e, responseStatus,
                    RetryAfter.millis(conn.getHeaderField("Retry-After"), -1), url);
        }
    }

    /** Decode only when the server says it encoded the representation. */
    static InputStream decodedStream(InputStream input, String contentEncoding)
            throws IOException {
        if (contentEncoding != null
                && contentEncoding.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
            return new GZIPInputStream(input);
        }
        return input;
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        private CountingInputStream(InputStream input) { super(input); }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) count++;
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) count += read;
            return read;
        }

        long count() { return count; }
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
        // A 2xx header followed by an I/O failure is not an HTTP outcome. The
        // server accepted the request and the transport lost the body (connection
        // reset, premature EOF, etc.); preserve it as an IOException so the batch
        // executor uses the availability retry budget instead of treating "200" as
        // a fatal status code.
        if (status >= 200 && status < 300) {
            return new batch.ResponseInterruptedException(message, error);
        }
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
