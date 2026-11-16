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

/** Domain-scale native Infobox acquisition through the shared batch executor. */
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
     * projected from it, decides which fields consume Wikipedia infobox values.
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
        if (declarations.isEmpty()) return new Result(0, 0, 0, List.of());
        Map<String, List<WikidataDynamicObject>> targets = new LinkedHashMap<>();
        for (WikidataDynamicObject object : pool) {
            if (object != null && WikidataIds.isQid(object.qid())
                    && declarations.stream().anyMatch(d ->
                            needsAcquisition(model, object, d))) {
                targets.computeIfAbsent(object.qid(), ignored -> new ArrayList<>()).add(object);
            }
        }
        Map<String, List<WikidataDynamicObject>> titleTargets =
                WikipediaArticleCorrespondence.byTitle(
                        targets.values().stream().flatMap(List::stream).toList(), api, log);
        var pages = new java.util.concurrent.atomic.AtomicInteger();
        var values = new java.util.concurrent.atomic.AtomicInteger();
        var batches = new java.util.concurrent.atomic.AtomicInteger();
        Map<String, FieldOutcome> outcomes = new LinkedHashMap<>();
        List<String> titles = List.copyOf(titleTargets.keySet());
        boolean needsRendered = declarations.stream().anyMatch(Declaration::rendered);
        try (GenerationLog.Group group = log.group("Acquire native Wikipedia infoboxes — "
                + titles.size() + " article(s) — " + (needsRendered
                ? titles.size() + " rendered request(s)"
                : ((titles.size() + BATCH - 1) / BATCH) + " batch request(s)"))) {
            List<batch.WorkUnit<Map<String, Acquired>>> units = new ArrayList<>();
            if (needsRendered) {
                for (String title : titles) units.add(renderedUnit(title, fetcher));
                group.message("Rendered infobox fields require one MediaWiki parse request "
                        + "per article; " + titles.size() + " request(s) are queued.\n");
            } else {
                for (int from = 0; from < titles.size(); from += BATCH) {
                    units.add(unit(List.copyOf(titles.subList(from,
                            Math.min(titles.size(), from + BATCH))), group, fetcher));
                }
            }
            List<batch.WorkDescriptor> failed =
                    new batch.BatchExecutor<Map<String, Acquired>>(
                    batch.BatchPolicy.defaults().withResume(false), batchProgress(group),
                    wikidata.WikidataBatchFailureClassifier.INSTANCE, cancellation,
                    // How hard a run may work is one policy; Wikipedia is not exempt
                    // from the network profile the reader chose for Wikidata.
                    batch.BatchCheckpointStore.NONE, api.entityConcurrency())
                    .runBestEffort(units, (descriptor, result) -> {
                        result.forEach((title, answer) -> {
                            List<WikidataDynamicObject> copies = titleTargets.get(title);
                            if (copies == null || copies.isEmpty()) return;
                            InfoboxParameters acquired = answer == null
                                    ? null : answer.evidence();
                            if (acquired != null) pages.incrementAndGet();
                            // What this page SUPPLIED, counted once however many in-memory
                            // views of the entity received it. Adding up the copies made a
                            // reported count depend on carrier history rather than on what
                            // was acquired.
                            java.util.Set<String> supplied = new java.util.LinkedHashSet<>();
                            for (WikidataDynamicObject object : copies) {
                                List<Declaration> pending = pendingFieldDeclarations(
                                        model, object, declarations);
                                // One source document belongs to every in-memory view of
                                // this entity. Keeping it on only the first QID copy made
                                // provenance depend on registry/carrier history.
                                object.infoboxEvidence(acquired,
                                        answer != null && answer.parametersAnswered(),
                                        answer != null && answer.renderedAnswered());
                                List<String> objectSupplied = List.of();
                                if (acquired != null) {
                                    objectSupplied = fill(object, pending, acquired);
                                    supplied.addAll(objectSupplied);
                                }
                                recordOutcomes(object, pending, objectSupplied, outcomes);
                            }
                            values.addAndGet(supplied.size());
                        });
                        batches.incrementAndGet();
                    });
            for (batch.WorkDescriptor descriptor : failed) {
                String title = descriptor.parameters().get("title");
                if (title == null) {
                    title = descriptor.parameters().get("titles");
                }
                if (title == null) continue;
                for (String failedTitle : title.split("\\|")) {
                    List<WikidataDynamicObject> copies = titleTargets.get(failedTitle);
                    if (copies == null) continue;
                    for (WikidataDynamicObject object : copies) {
                        recordFailedOutcomes(model, object, declarations, outcomes,
                                "Wikipedia did not return " + failedTitle);
                    }
                }
            }
        }
        log.message("Wikipedia infoboxes: " + values.get() + " value(s) from " + pages.get()
                + " page(s), " + batches.get() + " batch(es).\n");
        return new Result(pages.get(), values.get(), batches.get(),
                List.copyOf(outcomes.values()));
    }

    /** Records only fields that were pending when this article acquisition began.
     * {@link #fill} is the one interpretation and reports which of those it supplied. */
    private static void recordOutcomes(WikidataDynamicObject object,
            List<Declaration> pending, List<String> supplied,
            Map<String, FieldOutcome> outcomes) {
        for (Declaration declaration : pending) {
            OutcomeState state = supplied.contains(declarationKey(declaration))
                    ? OutcomeState.VALUE : OutcomeState.ANSWERED_EMPTY;
            FieldOutcome outcome = new FieldOutcome(object.qid(), object.getDisplayName(),
                    object.identityTypeName(), declaration.owner().className(),
                    declaration.field().name(), state, "");
            outcomes.put(outcome.key(), outcome);
        }
    }

    private static void recordFailedOutcomes(GeneratedProjectModel model,
            WikidataDynamicObject object, List<Declaration> declarations,
            Map<String, FieldOutcome> outcomes, String message) {
        for (Declaration declaration : declarations) {
            if (!declaration.fillField()
                    || !needsAcquisition(model, object, declaration)) continue;
            FieldOutcome outcome = new FieldOutcome(object.qid(), object.getDisplayName(),
                    object.identityTypeName(), declaration.owner().className(),
                    declaration.field().name(), OutcomeState.FAILED, message);
            outcomes.putIfAbsent(outcome.key(), outcome);
        }
    }

    /** Interpretation is a read of the acquired evidence, never of the response: a value
     *  the run keeps must be one the recorded document supports. */
    private static List<String> fill(WikidataDynamicObject object,
            List<Declaration> pending,
            InfoboxParameters acquired) {
        List<String> filled = new ArrayList<>();
        for (Declaration declaration : pending) {
            String value = acquired.valueOf(
                    InfoboxParameters.Key.parse(declaration.parameter()));
            if (value != null && !value.isBlank()) {
                object.put(declaration.field().name(), value);
                filled.add(declarationKey(declaration));
            }
        }
        return filled;
    }

    private static List<Declaration> pendingFieldDeclarations(
            GeneratedProjectModel model, WikidataDynamicObject object,
            List<Declaration> declarations) {
        return declarations.stream()
                .filter(Declaration::fillField)
                .filter(declaration -> needsAcquisition(model, object, declaration))
                .toList();
    }

    private static boolean needsAcquisition(GeneratedProjectModel model,
            WikidataDynamicObject object, Declaration declaration) {
        return FieldSourceTargeting.applies(
                model, object, declaration.owner(), declaration.target())
                && !answered(object, declaration)
                && (!declaration.fillField()
                    || object.get(declaration.field().name()) == null);
    }

    private static String declarationKey(Declaration declaration) {
        return declaration.owner().className() + "." + declaration.field().name();
    }

    private static batch.WorkUnit<Map<String, Acquired>> unit(
            List<String> titles, GenerationLog log, Fetcher fetcher) {
        return new batch.WorkUnit<>() {
            @Override public batch.WorkDescriptor descriptor() {
                String names = String.join("|", titles);
                return new batch.WorkDescriptor("wikipedia-infoboxes", "infoboxes:" + names,
                        "Wikipedia infoboxes " + titles.size() + " articles",
                        Map.of("titles", names));
            }
            @Override public String request() { return url(titles); }
            @Override public Map<String, Acquired> execute() throws Exception {
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
                Map<String, Acquired> out = new LinkedHashMap<>();
                for (String requested : titles) {
                    String actual = requested;
                    for (int i = 0; i < 4 && aliases.containsKey(actual); i++)
                        actual = aliases.get(actual);
                    // A requested article with no infobox is still ANSWERED: null records
                    // that this page was read and had none, so it is not read again.
                    out.put(requested, new Acquired(parsed.get(actual), true, false));
                }
                return out;
            }
            @Override public List<? extends batch.WorkUnit<Map<String, Acquired>>>
                    split() {
                if (titles.size() < 2) return List.of();
                int middle = titles.size() / 2;
                return List.of(unit(titles.subList(0, middle), log, fetcher),
                        unit(titles.subList(middle, titles.size()), log, fetcher));
            }
        };
    }

    /** Rendered rows are calculated by MediaWiki, so unlike source revisions they cannot
     * be retrieved for fifty titles in one action=query request. They still use the shared
     * batch executor for concurrency, progress, cancellation and 429 handling. */
    private static batch.WorkUnit<Map<String, Acquired>> renderedUnit(
            String title, Fetcher fetcher) {
        return new batch.WorkUnit<>() {
            @Override public batch.WorkDescriptor descriptor() {
                return new batch.WorkDescriptor("wikipedia-rendered-infobox",
                        "rendered-infobox:" + title, "Wikipedia infobox for " + title,
                        Map.of("title", title));
            }
            @Override public String request() { return renderedUrl(title); }
            @Override public Map<String, Acquired> execute() throws Exception {
                JsonNode root = JSON.readTree(fetcher.fetch(URI.create(renderedUrl(title))));
                InfoboxParameters evidence = wikipedia.WikipediaInfoboxClient
                        .parseResponse(root);
                return Map.of(title, new Acquired(evidence, true, true));
            }
            @Override public List<? extends batch.WorkUnit<Map<String, Acquired>>> split() {
                return List.of();
            }
        };
    }

    private static String url(List<String> titles) {
        return "https://en.wikipedia.org/w/api.php?action=query&prop=revisions"
                + "&rvprop=ids%7Ccontent&rvslots=main&redirects=1&format=json"
                + "&formatversion=2&titles=" + encode(String.join("|", titles));
    }

    private static String renderedUrl(String title) {
        return "https://en.wikipedia.org/w/api.php?action=parse"
                + "&prop=wikitext%7Ctext%7Crevid&redirects=1&format=json&formatversion=2&page="
                + encode(title);
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
                    result.add(new Declaration(
                            owner, field, source(field).propertyPid(), null, true));
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
            if (!WikipediaDatasourceProvider.FAMILY_INFOBOX_FIELD.equals(
                    step.prepared().familyId())) continue;
            WikipediaDatasourceProvider.InfoboxValueSpec specification =
                    step.prepared().configuration(
                            WikipediaDatasourceProvider.InfoboxValueSpec.class);
            // Compatibility with plans prepared before InfoboxValueSpec recorded
            // whether a row fills a field or is retained only as candidate evidence.
            String legacy = step.prepared().configuration(String.class);
            String parameter = specification == null ? legacy : specification.key();
            if (parameter == null) continue;
            GeneratedClassModel owner = model.findClass(step.target().className());
            GeneratedFieldModel field = wikidata.explore.model.OwnedFieldSources
                    .declaredField(model, step.target().className(),
                            step.target().fieldPath());
            if (owner == null || field == null) {
                throw new IllegalArgumentException("Datasource plan targets unknown field "
                        + step.target().className() + "." + step.target().fieldPath());
            }
            if (InfoboxParameters.Key.parse(parameter) == null) {
                throw new IllegalArgumentException("Invalid Wikipedia infobox value for "
                        + step.target().className() + "." + step.target().fieldPath()
                        + ": " + parameter);
            }
            result.add(new Declaration(owner, field, parameter, step.target(),
                    specification == null || specification.fillField()));
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

    /** Which of a field's mappings names an infobox value, for the pre-binding
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

    private static boolean answered(WikidataDynamicObject object, Declaration declaration) {
        return declaration.rendered()
                ? object.renderedInfoboxAnswered() : object.infoboxAnswered();
    }
    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
    private record Declaration(
            GeneratedClassModel owner, GeneratedFieldModel field, String parameter,
            datasource.api.SourceBindingTarget target, boolean fillField) {
        private Declaration(GeneratedClassModel owner, GeneratedFieldModel field,
                String parameter) {
            this(owner, field, parameter, null, true);
        }

        private boolean rendered() {
            InfoboxParameters.Key key = InfoboxParameters.Key.parse(parameter);
            return key != null && key.rendered();
        }
    }
    private record Acquired(InfoboxParameters evidence, boolean parametersAnswered,
                            boolean renderedAnswered) { }
    public enum OutcomeState { VALUE, ANSWERED_EMPTY, FAILED }

    /** One configured field's observable acquisition result for one source entity.
     *  The durable facts remain on the entity itself: retained evidence/value for VALUE,
     *  an answered flag for ANSWERED_EMPTY, and no answered flag for FAILED. */
    public record FieldOutcome(String qid, String instance, String identityType,
            String className,
            String field, OutcomeState state, String message) {
        public FieldOutcome {
            qid = qid == null ? "" : qid;
            instance = instance == null ? "" : instance;
            identityType = identityType == null ? "" : identityType;
            className = className == null ? "" : className;
            field = field == null ? "" : field;
            message = message == null ? "" : message;
        }

        private String key() {
            return identityType + "\n" + qid + "\n" + className + "\n" + field;
        }
    }

    public record Result(int pages, int values, int batches,
                         List<FieldOutcome> outcomes) {
        public Result {
            outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        }

        public long count(OutcomeState state) {
            return outcomes.stream().filter(outcome -> outcome.state() == state).count();
        }

        public boolean complete() { return count(OutcomeState.FAILED) == 0; }
    }
}
