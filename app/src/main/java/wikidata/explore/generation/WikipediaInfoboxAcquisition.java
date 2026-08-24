package wikidata.explore.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datasource.evidence.InfoboxParameters;
import datasource.api.BindingScope;
import datasource.api.SourceExecutionPlan;
import datasource.wikipedia.WikipediaDatasourceProvider;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Domain-scale native Infobox acquisition: one revision request per 50 articles. */
public final class WikipediaInfoboxAcquisition {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BATCH = 50;
    private WikipediaInfoboxAcquisition() { }

    public static Result apply(GeneratedProjectModel model, List<WikidataDynamicObject> pool,
            GenerationLog log, work.CancellationToken cancellation,
            wikidata.api.WikidataApiClient api) throws Exception {
        return apply(model, pool, log, cancellation, api,
                WikipediaInfoboxAcquisition::fetchRemote);
    }

    /**
     * Plan-driven entry point. The resolved binding, rather than the legacy mapping
     * projected from it, decides which fields consume Wikipedia infobox parameters.
     * The old overload remains for callers outside a model execution workflow.
     */
    public static Result apply(GeneratedProjectModel model, List<WikidataDynamicObject> pool,
            GenerationLog log, work.CancellationToken cancellation,
            wikidata.api.WikidataApiClient api, SourceExecutionPlan sourcePlan)
            throws Exception {
        return apply(model, pool, log, cancellation, api,
                WikipediaInfoboxAcquisition::fetchRemote, sourcePlan);
    }

    static Result apply(GeneratedProjectModel model, List<WikidataDynamicObject> pool,
            GenerationLog log, work.CancellationToken cancellation,
            wikidata.api.WikidataApiClient api, Fetcher fetcher) throws Exception {
        return apply(model, pool, log, cancellation, api, fetcher, null);
    }

    static Result apply(GeneratedProjectModel model, List<WikidataDynamicObject> pool,
            GenerationLog log, work.CancellationToken cancellation,
            wikidata.api.WikidataApiClient api, Fetcher fetcher,
            SourceExecutionPlan sourcePlan) throws Exception {
        List<Declaration> declarations = sourcePlan == null
                ? declarations(model) : declarations(model, sourcePlan);
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
                api.getEntities(List.copyOf(targets.keySet()), List.of(),
                        java.util.Set.of(wikidata.api.FactDemand.EntityMetadata.SITELINKS),
                        log.batchSink());
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
                            // What this page SUPPLIED, counted once however many in-memory
                            // views of the entity received it. Adding up the copies made a
                            // reported count depend on carrier history rather than on what
                            // was acquired.
                            java.util.Set<String> supplied = new java.util.LinkedHashSet<>();
                            for (WikidataDynamicObject object : copies) {
                                // One source document belongs to every in-memory view of
                                // this entity. Keeping it on only the first QID copy made
                                // provenance depend on registry/carrier history.
                                object.infoboxParameters(acquired);
                                if (acquired != null) {
                                    supplied.addAll(fill(model, object, declarations, acquired));
                                }
                            }
                            values.addAndGet(supplied.size());
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
    private static List<String> fill(GeneratedProjectModel model,
            WikidataDynamicObject object, List<Declaration> declarations,
            InfoboxParameters acquired) {
        List<String> filled = new ArrayList<>();
        for (Declaration declaration : declarations) {
            if (!applies(model, object, declaration)
                    || object.get(declaration.field().name()) != null) continue;
            String value = acquired.valueOf(
                    InfoboxParameters.Key.parse(declaration.parameter()));
            if (value != null && !value.isBlank()) {
                object.put(declaration.field().name(), value);
                filled.add(declaration.owner().className() + "." + declaration.field().name());
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
                    parsed.put(title, InfoboxParameters.fromEnglishWikipedia(
                            box.template(), box.parameters(), title,
                            revisions.get(0).path("revid").asText("")));
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
                        && InfoboxParameters.Key.parse(source(field).propertyPid()) != null) {
                    result.add(new Declaration(owner, field, source(field).propertyPid()));
                }
            }
        }
        return result;
    }

    private static List<Declaration> declarations(
            GeneratedProjectModel model, SourceExecutionPlan sourcePlan) {
        List<Declaration> result = new ArrayList<>();
        if (model == null || sourcePlan == null) return result;
        for (SourceExecutionPlan.Step step : sourcePlan.steps(BindingScope.FIELD_VALUE)) {
            String parameter =
                    WikipediaDatasourceProvider.infoboxParameter(step.binding());
            if (parameter == null) continue;
            GeneratedClassModel owner = model.findClass(step.target().className());
            GeneratedFieldModel field = declaredField(owner, step.target().fieldPath());
            if (owner == null || field == null) {
                throw new IllegalArgumentException("Datasource plan targets unknown field "
                        + step.target().className() + "." + step.target().fieldPath());
            }
            if (InfoboxParameters.Key.parse(parameter) == null) {
                throw new IllegalArgumentException("Invalid Wikipedia infobox parameter for "
                        + step.target().className() + "." + step.target().fieldPath()
                        + ": " + parameter);
            }
            result.add(new Declaration(owner, field, parameter));
        }
        return List.copyOf(result);
    }

    private static GeneratedFieldModel declaredField(
            GeneratedClassModel owner, String path) {
        if (owner == null || path == null || path.isBlank()) return null;
        List<GeneratedFieldModel> fields = owner.fields();
        GeneratedFieldModel found = null;
        for (String segment : path.split("\\.")) {
            found = fields.stream().filter(field -> field != null
                    && segment.equals(field.name())).findFirst().orElse(null);
            if (found == null) return null;
            fields = found.fields();
        }
        return found;
    }

    /** Which of a field's mappings names an infobox parameter, for the pre-binding
     *  path only. A model execution workflow asks
     *  {@link WikipediaDatasourceProvider#infoboxParameter} of the BINDING instead —
     *  that is the one answer the message, the explanation and this acquisition share. */
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
    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
    private record Declaration(
            GeneratedClassModel owner, GeneratedFieldModel field, String parameter) { }
    public record Result(int pages, int values, int batches) { }
}
