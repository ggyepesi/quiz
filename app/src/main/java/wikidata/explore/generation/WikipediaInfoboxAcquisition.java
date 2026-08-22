package wikidata.explore.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datasource.evidence.ContentDigest;
import datasource.evidence.InfoboxParameters;
import datasource.evidence.SourceDocument;
import wikidata.WikidataIds;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
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
import java.util.TreeMap;

/** Domain-scale native Infobox acquisition: one revision request per 50 articles. */
public final class WikipediaInfoboxAcquisition {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BATCH = 50;
    private WikipediaInfoboxAcquisition() { }

    public static Result apply(GeneratedProjectModel model, List<WikidataDynamicObject> pool,
            GenerationLog log, process.CancellationToken cancellation,
            wikidata.api.WikidataApiClient api) throws Exception {
        return apply(model, pool, log, cancellation, api,
                WikipediaInfoboxAcquisition::fetchRemote);
    }

    static Result apply(GeneratedProjectModel model, List<WikidataDynamicObject> pool,
            GenerationLog log, process.CancellationToken cancellation,
            wikidata.api.WikidataApiClient api, Fetcher fetcher) throws Exception {
        List<Declaration> declarations = declarations(model);
        if (declarations.isEmpty()) return new Result(0, 0, 0);
        Map<String, List<WikidataDynamicObject>> targets = new LinkedHashMap<>();
        for (WikidataDynamicObject object : pool) {
            if (object != null && !object.isPart() && WikidataIds.isQid(object.qid())
                    && !object.infoboxAnswered()
                    && declarations.stream().anyMatch(d -> applies(model, object, d)
                    && object.get(d.field().name()) == null)) {
                targets.computeIfAbsent(object.qid(), ignored -> new ArrayList<>()).add(object);
            }
        }
        Map<String, wikidata.api.WikidataApiClient.ApiEntity> entities =
                api.getEntities(List.copyOf(targets.keySet()), List.of(), log.batchSink());
        Map<String, String> titleToQid = new LinkedHashMap<>();
        entities.forEach((qid, entity) -> {
            if (entity != null && !entity.enwikiTitle().isBlank())
                titleToQid.putIfAbsent(entity.enwikiTitle(), qid);
        });
        var pages = new java.util.concurrent.atomic.AtomicInteger();
        var values = new java.util.concurrent.atomic.AtomicInteger();
        var batches = new java.util.concurrent.atomic.AtomicInteger();
        List<String> titles = List.copyOf(titleToQid.keySet());
        try (GenerationLog.Group group = log.group("Acquire native Wikipedia infoboxes — "
                + titles.size() + " article(s) in batches of " + BATCH)) {
            List<batch.WorkUnit<Map<String, InfoboxParameters>>> units = new ArrayList<>();
            for (int from = 0; from < titles.size(); from += BATCH) {
                units.add(unit(List.copyOf(titles.subList(from,
                        Math.min(titles.size(), from + BATCH))), group, fetcher));
            }
            new batch.BatchExecutor<Map<String, InfoboxParameters>>(
                    batch.BatchPolicy.defaults().withResume(false), batchProgress(group),
                    wikidata.WikidataBatchFailureClassifier.INSTANCE, cancellation,
                    // How hard a run may work is one policy; Wikipedia is not exempt
                    // from the network profile the reader chose for Wikidata.
                    batch.BatchCheckpointStore.NONE, api.entityConcurrency())
                    .run(units, (descriptor, result) -> {
                        result.forEach((title, acquired) -> {
                            List<WikidataDynamicObject> copies =
                                    targets.get(titleToQid.get(title));
                            if (copies == null || copies.isEmpty()) return;
                            if (acquired != null) pages.incrementAndGet();
                            for (WikidataDynamicObject object : copies) {
                                // One source document belongs to every in-memory view of
                                // this entity. Keeping it on only the first QID copy made
                                // provenance depend on registry/carrier history.
                                object.infoboxParameters(acquired);
                                if (acquired != null) {
                                    values.addAndGet(fill(model, object, declarations, acquired));
                                }
                            }
                        });
                        batches.incrementAndGet();
                    });
        }
        log.message("Wikipedia infoboxes: " + values.get() + " value(s) from " + pages.get()
                + " page(s), " + batches.get() + " batch(es).\n");
        return new Result(pages.get(), values.get(), batches.get());
    }

    /** Interpretation is a read of the acquired evidence, never of the response: a value
     *  the run keeps must be one the recorded document supports. */
    private static int fill(GeneratedProjectModel model, WikidataDynamicObject object,
            List<Declaration> declarations, InfoboxParameters acquired) {
        int filled = 0;
        for (Declaration declaration : declarations) {
            if (!applies(model, object, declaration)
                    || object.get(declaration.field().name()) != null) continue;
            String[] key = split(source(declaration.field()).propertyPid());
            if (key == null || !acquired.isTemplate(key[0])) continue;
            String value = acquired.value(key[1]);
            if (value != null && !value.isBlank()) {
                object.put(declaration.field().name(), value);
                filled++;
            }
        }
        return filled;
    }

    private static batch.WorkUnit<Map<String, InfoboxParameters>> unit(
            List<String> titles, GenerationLog log, Fetcher fetcher) {
        return new batch.WorkUnit<>() {
            @Override public batch.WorkDescriptor descriptor() {
                String names = String.join("|", titles);
                return new batch.WorkDescriptor("wikipedia-infoboxes", "infoboxes:" + names,
                        "Wikipedia infoboxes " + titles.size() + " articles",
                        Map.of("titles", names));
            }
            @Override public String request() { return url(titles); }
            @Override public Map<String, InfoboxParameters> execute() throws Exception {
                JsonNode root = JSON.readTree(fetcher.fetch(URI.create(url(titles))));
                Map<String, String> aliases = new LinkedHashMap<>();
                for (JsonNode value : root.path("query").path("normalized"))
                    aliases.put(value.path("from").asText(""), value.path("to").asText(""));
                for (JsonNode value : root.path("query").path("redirects"))
                    aliases.put(value.path("from").asText(""), value.path("to").asText(""));
                Map<String, InfoboxParameters> parsed = new LinkedHashMap<>();
                for (JsonNode page : root.path("query").path("pages")) {
                    if (page.path("missing").asBoolean(false)) continue;
                    JsonNode revisions = page.path("revisions");
                    if (!revisions.isArray() || revisions.isEmpty()) continue;
                    JsonNode main = revisions.get(0).path("slots").path("main");
                    String text = main.path("content").asText(main.path("*").asText(""));
                    var box = wikipedia.WikipediaInfoboxClient.parseWikitext(text);
                    if (box == null) continue;
                    String title = page.path("title").asText("");
                    parsed.put(title, new InfoboxParameters(box.template(), box.parameters(),
                            document(title, revisions.get(0).path("revid").asText(""),
                                    box.template(), box.parameters())));
                }
                Map<String, InfoboxParameters> out = new LinkedHashMap<>();
                for (String requested : titles) {
                    String actual = requested;
                    for (int i = 0; i < 4 && aliases.containsKey(actual); i++)
                        actual = aliases.get(actual);
                    // A requested article with no infobox is still ANSWERED: null records
                    // that this page was read and had none, so it is not read again.
                    out.put(requested, parsed.get(actual));
                }
                return out;
            }
            @Override public List<? extends batch.WorkUnit<Map<String, InfoboxParameters>>>
                    split() {
                if (titles.size() < 2) return List.of();
                int middle = titles.size() / 2;
                return List.of(unit(titles.subList(0, middle), log, fetcher),
                        unit(titles.subList(middle, titles.size()), log, fetcher));
            }
        };
    }

    /** Digests the PARAMETERS read, not the article body: they are the evidence, and a
     *  page whose prose changes while its infobox does not still supports the value. */
    private static SourceDocument document(String title, String revision, String template,
            Map<String, String> parameters) {
        StringBuilder material = new StringBuilder(template);
        new TreeMap<>(parameters).forEach((name, value) ->
                material.append('\n').append(name).append('=').append(value));
        // No revision rather than the word "unknown": a document is compared by revision
        // AND digest, and a sentinel equal to itself would report an edited page as the
        // same version. The digest carries the version on its own.
        return new SourceDocument("Wikipedia (English)", title, title,
                "https://en.wikipedia.org/wiki/" + encode(title.replace(' ', '_')),
                revision, new ContentDigest("sha256", sha256(material.toString())),
                Instant.now().toString());
    }

    private static String url(List<String> titles) {
        return "https://en.wikipedia.org/w/api.php?action=query&prop=revisions"
                + "&rvprop=ids%7Ccontent&rvslots=main&redirects=1&format=json"
                + "&formatversion=2&titles=" + encode(String.join("|", titles));
    }

    private static String fetchRemote(URI uri) throws Exception {
        try (java.io.InputStream in = objectview.utils.UrlOpener.open(uri.toURL())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface interface Fetcher { String fetch(URI uri) throws Exception; }

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

    private static List<Declaration> declarations(GeneratedProjectModel model) {
        List<Declaration> result = new ArrayList<>();
        if (model == null) return result;
        for (GeneratedClassModel owner : model.classes()) {
            for (GeneratedFieldModel field : owner.effectiveFields(model)) {
                if (field != null && source(field) != null
                        && split(source(field).propertyPid()) != null) {
                    result.add(new Declaration(owner, field));
                }
            }
        }
        return result;
    }

    /** Which of a field's mappings names an infobox parameter — asked HERE so the
     *  pipeline, its explanation and this acquisition cannot answer it differently. */
    public static wikidata.explore.model.FieldSourceMapping source(
            GeneratedFieldModel field) {
        if (field == null) return null;
        if (field.mapping() != null
                && field.mapping().sourceType() == FieldSourceType.WIKIPEDIA_INFOBOX
                && !field.mapping().propertyPid().isBlank()) {
            return field.mapping();
        }
        return field.fallbackMapping() != null
                && field.fallbackMapping().sourceType() == FieldSourceType.WIKIPEDIA_INFOBOX
                && !field.fallbackMapping().propertyPid().isBlank()
                ? field.fallbackMapping() : null;
    }

    private static boolean applies(GeneratedProjectModel model, WikidataDynamicObject object,
            Declaration declaration) {
        for (String direct : object.directClassNames()) {
            for (GeneratedClassModel current = model.findClass(direct); current != null;
                    current = current.baseClassName().isBlank()
                            ? null : model.findClass(current.baseClassName())) {
                if (current.className().equals(declaration.owner().className())) return true;
            }
        }
        return false;
    }
    private static String[] split(String key) {
        if (key == null) return null;
        int dot = key.indexOf('.');
        return dot <= 0 || dot == key.length() - 1 ? null
                : new String[]{key.substring(0, dot).trim(), key.substring(dot + 1).trim()};
    }
    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private record Declaration(GeneratedClassModel owner, GeneratedFieldModel field) { }
    public record Result(int pages, int values, int batches) { }
}
