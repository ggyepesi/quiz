package wikidata.explore.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datasource.evidence.CategoryMembership;
import datasource.evidence.ContentDigest;
import datasource.evidence.SourceDocument;
import wikidata.WikidataIds;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bulk acquisition of uninterpreted English Wikipedia category memberships. */
public final class WikipediaCategoryAcquisition {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BATCH = 50;

    private WikipediaCategoryAcquisition() {}

    public static Result apply(GeneratedProjectModel model, List<WikidataDynamicObject> pool,
                               GenerationLog log, process.CancellationToken cancellation,
                               wikidata.api.WikidataApiClient api) throws Exception {
        return apply(model, pool, log, cancellation, api,
                WikipediaCategoryAcquisition::fetchRemote);
    }

    static Result apply(GeneratedProjectModel model, List<WikidataDynamicObject> pool,
                        GenerationLog log, process.CancellationToken cancellation,
                        wikidata.api.WikidataApiClient api, Fetcher fetcher) throws Exception {
        if (!configured(model)) return new Result(0, 0, 0);
        java.util.Objects.requireNonNull(api, "Wikidata entity client is required");
        Map<String, WikidataDynamicObject> entities = new LinkedHashMap<>();
        for (WikidataDynamicObject value : pool) {
            if (value != null && WikidataIds.isQid(value.qid()) && !value.isPart()
                    && !value.categoryMembershipsAnswered()) {
                entities.putIfAbsent(value.qid(), value);
            }
        }
        java.util.concurrent.atomic.AtomicInteger pages = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger memberships = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger batches = new java.util.concurrent.atomic.AtomicInteger();
        List<String> qids = List.copyOf(entities.keySet());
        try (GenerationLog.Group group = log.group("Acquire Wikipedia categories — "
                + qids.size() + " entities in batches of " + BATCH)) {
            List<batch.WorkUnit<Map<String, List<CategoryMembership>>>> units = new ArrayList<>();
            for (int from = 0; from < qids.size(); from += BATCH) {
                List<String> unit = qids.subList(from, Math.min(qids.size(), from + BATCH));
                units.add(unit(List.copyOf(unit), group, cancellation, api, fetcher));
            }
            new batch.BatchExecutor<Map<String, List<CategoryMembership>>>(
                    batch.BatchPolicy.defaults().withResume(false), batchProgress(group),
                    wikidata.WikidataBatchFailureClassifier.INSTANCE, cancellation,
                    // How hard a run may work is one policy; Wikipedia is not exempt
                    // from the network profile the reader chose for Wikidata.
                    batch.BatchCheckpointStore.NONE,
                    api.entityConcurrency()).run(units, (descriptor, result) -> {
                        result.forEach((qid, acquired) -> {
                            WikidataDynamicObject entity = entities.get(qid);
                            if (entity == null) return;
                            entity.categoryMemberships(acquired);
                            pages.incrementAndGet();
                            memberships.addAndGet(acquired.size());
                        });
                        batches.incrementAndGet();
                    });
        }
        log.message("Wikipedia categories: " + memberships.get() + " membership(s) on "
                + pages.get() + " page(s), " + batches.get() + " batch(es).\n");
        return new Result(pages.get(), memberships.get(), batches.get());
    }

    private static batch.WorkUnit<Map<String, List<CategoryMembership>>> unit(
            List<String> qids, GenerationLog log, process.CancellationToken cancellation,
            wikidata.api.WikidataApiClient api, Fetcher fetcher) {
        return new batch.WorkUnit<>() {
            @Override public batch.WorkDescriptor descriptor() {
                String ids = String.join(",", qids);
                return new batch.WorkDescriptor("wikipedia-categories", "categories:" + ids,
                        "Wikipedia categories " + qids.size() + " entities", Map.of("ids", ids));
            }
            @Override public String request() {
                return "Wikidata sitelinks + Wikipedia categories for " + qids.size() + " QIDs";
            }
            @Override public Map<String, List<CategoryMembership>> execute() throws Exception {
                Map<String, String> titles = titles(qids, api, log);
                Map<String, Page> pages = categories(titles.values(), log, cancellation, fetcher);
                Map<String, List<CategoryMembership>> out = new LinkedHashMap<>();
                qids.forEach(qid -> out.put(qid, List.of()));
                titles.forEach((qid, title) -> {
                    Page page = pages.get(title);
                    if (page != null) out.put(qid, page.memberships());
                });
                return out;
            }
            @Override public List<? extends batch.WorkUnit<Map<String, List<CategoryMembership>>>> split() {
                if (qids.size() < 2) return List.of();
                int middle = qids.size() / 2;
                return List.of(unit(qids.subList(0, middle), log, cancellation, api, fetcher),
                        unit(qids.subList(middle, qids.size()), log, cancellation, api, fetcher));
            }
        };
    }

    private static batch.BatchProgress batchProgress(GenerationLog log) {
        return new batch.BatchProgress() {
            @Override public Running started(String title, String request) {
                GenerationLog.Running running = log.subqueryStarted(title, request);
                return new Running() {
                    @Override public void detail(String text) { log.message(text + "\n"); }
                    @Override public void done(String summary) { running.done(summary); }
                    @Override public void failed(String error) { running.failed(error); }
                };
            }
            @Override public void message(String text) { log.message(text); }
        };
    }

    public static boolean configured(GeneratedProjectModel model) {
        return model != null && model.classes().stream().filter(java.util.Objects::nonNull)
                .flatMap(c -> c.fields().stream()).filter(java.util.Objects::nonNull)
                .anyMatch(f -> f.wikipediaCategoryRule() != null
                        && f.wikipediaCategoryRule().configured());
    }

    /**
     * Which article an entity links to is answered by the entity documents this run has
     * already fetched — sitelinks ride the same request as labels and aliases. Asking
     * Wikidata again from here was a third way to answer one question, and it paid for
     * entities the fact store was already holding.
     */
    private static Map<String, String> titles(
            List<String> qids, wikidata.api.WikidataApiClient api, GenerationLog log)
            throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, wikidata.api.WikidataApiClient.ApiEntity> entities =
                api.getEntities(qids, List.of(), log.batchSink());
        entities.forEach((qid, entity) -> {
            if (entity != null && !entity.enwikiTitle().isBlank()) {
                out.put(qid, entity.enwikiTitle());
            }
        });
        return out;
    }

    private static Map<String, Page> categories(java.util.Collection<String> titles,
            GenerationLog log, process.CancellationToken cancellation, Fetcher fetcher)
            throws Exception {
        Map<String, PageBuilder> pages = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        if (titles.isEmpty()) return Map.of();
        String continuation = null;
        do {
            cancellation.throwIfCancelled();
            String url = "https://en.wikipedia.org/w/api.php?action=query"
                    + "&prop=categories%7Crevisions&cllimit=max&clshow=!hidden&rvprop=ids"
                    + "&redirects=1&format=json&formatversion=2&titles="
                    + encode(String.join("|", titles))
                    + (continuation == null ? "" : "&clcontinue=" + encode(continuation));
            JsonNode root = fetch(URI.create(url), "Read Wikipedia categories", log, fetcher);
            for (JsonNode normalized : root.path("query").path("normalized")) {
                aliases.put(normalized.path("from").asText(""),
                        normalized.path("to").asText(""));
            }
            for (JsonNode redirect : root.path("query").path("redirects")) {
                aliases.put(redirect.path("from").asText(""), redirect.path("to").asText(""));
            }
            for (JsonNode page : root.path("query").path("pages")) {
                if (page.path("missing").asBoolean(false)) continue;
                String title = page.path("title").asText("");
                PageBuilder builder = pages.computeIfAbsent(title, PageBuilder::new);
                if (page.path("revisions").isArray() && !page.path("revisions").isEmpty()) {
                    builder.revision = page.path("revisions").get(0).path("revid").asText("");
                }
                for (JsonNode category : page.path("categories")) {
                    String value = category.path("title").asText("")
                            .replaceFirst("^Category:", "").trim();
                    if (!value.isBlank() && !builder.categories.contains(value)) {
                        builder.categories.add(value);
                    }
                }
            }
            continuation = root.path("continue").path("clcontinue").asText("");
            if (continuation.isBlank()) continuation = null;
        } while (continuation != null);
        Map<String, Page> out = new LinkedHashMap<>();
        pages.forEach((title, builder) -> out.put(title, builder.build()));
        aliases.forEach((from, to) -> {
            String canonical = to;
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (aliases.containsKey(canonical) && seen.add(canonical)) {
                canonical = aliases.get(canonical);
            }
            Page page = out.get(canonical);
            if (page != null) out.put(from, page);
        });
        return out;
    }

    private static JsonNode fetch(URI uri, String title, GenerationLog log, Fetcher fetcher)
            throws Exception {
        GenerationLog.Running running = log.subqueryStarted(title, uri.toString());
        try {
            JsonNode result = JSON.readTree(fetcher.fetch(uri));
            running.done("OK");
            return result;
        } catch (Exception failure) {
            running.failed(failure.getMessage());
            throw failure;
        }
    }

    private static String fetchRemote(URI uri) throws Exception {
        try (java.io.InputStream in = objectview.utils.UrlOpener.open(uri.toURL())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface interface Fetcher { String fetch(URI uri) throws Exception; }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Digests the CATEGORIES read, not the article body: they are the evidence, and a
     *  page whose prose changes while its categories do not still supports the claim. */
    private static String digest(List<String> categories) {
        try {
            byte[] bytes = String.join("\n", categories).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class PageBuilder {
        final String title; String revision = ""; final List<String> categories = new ArrayList<>();
        PageBuilder(String title) { this.title = title; }
        Page build() {
            String url = "https://en.wikipedia.org/wiki/" + encode(title.replace(' ', '_'));
            // No revision rather than the word "unknown": a document is compared by
            // revision AND digest, and a sentinel that is equal to itself would report
            // an edited page as the same version. The digest below carries the version
            // on its own, which is what SourceDocument asks for.
            SourceDocument document = new SourceDocument("Wikipedia (English)", title, title,
                    url, revision, new ContentDigest("sha256", digest(categories)),
                    Instant.now().toString());
            return new Page(categories.stream().map(c -> new CategoryMembership(c, document)).toList());
        }
    }

    private record Page(List<CategoryMembership> memberships) {}
    public record Result(int pages, int memberships, int batches) {}
}
