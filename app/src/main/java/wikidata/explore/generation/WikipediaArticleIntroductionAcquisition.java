package wikidata.explore.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datasource.api.BindingScope;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceExecutionPlan;
import datasource.evidence.ArticleIntroduction;
import datasource.wikipedia.WikipediaDatasourceProvider;
import wikidata.WikidataIds;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Batched acquisition and interpretation of versioned Wikipedia article introductions. */
public final class WikipediaArticleIntroductionAcquisition {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BATCH = 50;

    private WikipediaArticleIntroductionAcquisition() { }

    public static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, GenerationLog log,
            work.CancellationToken cancellation, wikidata.api.WikidataApiClient api,
            SourceExecutionPlan plan) throws Exception {
        return apply(model, pool, log, cancellation, api, plan,
                WikipediaArticleIntroductionAcquisition::fetchRemote);
    }

    static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, GenerationLog log,
            work.CancellationToken cancellation, wikidata.api.WikidataApiClient api,
            SourceExecutionPlan plan, Fetcher fetcher) throws Exception {
        List<Declaration> declarations = declarations(model, plan);
        if (declarations.isEmpty() || pool == null || pool.isEmpty()) {
            return new Result(0, 0, 0);
        }
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        work.CancellationToken token = cancellation == null
                ? new work.CancellationToken() : cancellation;
        java.util.concurrent.atomic.AtomicInteger values =
                new java.util.concurrent.atomic.AtomicInteger();

        // Reinterpret retained evidence before deciding that a request is necessary.
        for (WikidataDynamicObject object : pool) {
            ArticleIntroduction retained = object == null
                    ? null : object.articleIntroduction();
            if (retained != null) values.addAndGet(fill(
                    model, object, declarations, retained).size());
        }

        List<WikidataDynamicObject> targets = pool.stream()
                .filter(java.util.Objects::nonNull)
                .filter(object -> WikidataIds.isQid(object.qid()))
                .filter(object -> !object.articleIntroductionAnswered())
                .filter(object -> declarations.stream().anyMatch(declaration ->
                        FieldSourceTargeting.applies(model, object,
                                declaration.owner(), declaration.target())
                                && object.get(declaration.field().name()) == null))
                .toList();
        Map<String, List<WikidataDynamicObject>> byTitle =
                WikipediaArticleCorrespondence.byTitle(targets, api, sink);
        List<String> titles = List.copyOf(byTitle.keySet());
        if (titles.isEmpty()) return new Result(0, values.get(), 0);

        java.util.concurrent.atomic.AtomicInteger pages =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger batches =
                new java.util.concurrent.atomic.AtomicInteger();
        try (GenerationLog.Group group = sink.group(
                "Acquire Wikipedia article introductions — " + titles.size()
                        + " article(s) in batches of " + BATCH)) {
            List<batch.WorkUnit<Map<String, Acquired>>> units = new ArrayList<>();
            for (int from = 0; from < titles.size(); from += BATCH) {
                units.add(unit(List.copyOf(titles.subList(from,
                        Math.min(titles.size(), from + BATCH))), fetcher));
            }
            new batch.BatchExecutor<Map<String, Acquired>>(
                    batch.BatchPolicy.defaults().withResume(false), group.batchProgress(),
                    wikidata.WikidataBatchFailureClassifier.INSTANCE, token,
                    batch.BatchCheckpointStore.NONE,
                    api == null ? 1 : api.entityConcurrency())
                    .run(units, (descriptor, result) -> {
                        result.forEach((title, acquired) -> {
                            List<WikidataDynamicObject> copies = byTitle.get(title);
                            if (copies == null || copies.isEmpty()) return;
                            ArticleIntroduction evidence = acquired == null
                                    ? null : acquired.evidence();
                            if (evidence != null && !evidence.text().isBlank()) {
                                pages.incrementAndGet();
                            }
                            LinkedHashSet<String> supplied = new LinkedHashSet<>();
                            for (WikidataDynamicObject object : copies) {
                                object.articleIntroductionEvidence(evidence, true);
                                if (evidence != null) supplied.addAll(
                                        fill(model, object, declarations, evidence));
                            }
                            values.addAndGet(supplied.size());
                        });
                        batches.incrementAndGet();
                    });
        }
        sink.message("Wikipedia article introductions: " + values.get()
                + " field value(s) from " + pages.get() + " page(s), "
                + batches.get() + " batch(es).\n");
        return new Result(pages.get(), values.get(), batches.get());
    }

    private static List<Declaration> declarations(
            GeneratedProjectModel model, SourceExecutionPlan plan) {
        if (model == null || plan == null) return List.of();
        List<Declaration> result = new ArrayList<>();
        for (SourceExecutionPlan.Step step : plan.steps(BindingScope.FIELD_VALUE)) {
            if (!WikipediaDatasourceProvider.FAMILY_ARTICLE_INTRODUCTION_FIELD.equals(
                    step.prepared().familyId())) continue;
            GeneratedClassModel owner = model.findClass(step.target().className());
            GeneratedFieldModel field = wikidata.explore.model.OwnedFieldSources
                    .declaredField(model, step.target().className(),
                            step.target().fieldPath());
            if (owner == null || field == null) {
                throw new IllegalArgumentException("Datasource plan targets unknown field "
                        + step.target().className() + "." + step.target().fieldPath());
            }
            result.add(new Declaration(owner, field, step.target()));
        }
        return List.copyOf(result);
    }

    private static List<String> fill(GeneratedProjectModel model,
            WikidataDynamicObject object, List<Declaration> declarations,
            ArticleIntroduction evidence) {
        if (evidence == null || evidence.text().isBlank()) return List.of();
        List<String> filled = new ArrayList<>();
        for (Declaration declaration : declarations) {
            if (!FieldSourceTargeting.applies(
                    model, object, declaration.owner(), declaration.target())
                    || object.get(declaration.field().name()) != null) continue;
            object.put(declaration.field().name(), evidence.text());
            filled.add(declaration.owner().className() + "."
                    + declaration.field().name());
        }
        return filled;
    }

    private static batch.WorkUnit<Map<String, Acquired>> unit(
            List<String> titles, Fetcher fetcher) {
        return new batch.WorkUnit<>() {
            @Override public batch.WorkDescriptor descriptor() {
                String names = String.join("|", titles);
                return new batch.WorkDescriptor("wikipedia-article-introductions",
                        "article-introductions:" + names,
                        "Wikipedia introductions " + titles.size() + " articles",
                        Map.of("titles", names));
            }
            @Override public String request() { return url(titles); }
            @Override public Map<String, Acquired> execute() throws Exception {
                return parse(JSON.readTree(fetcher.fetch(URI.create(url(titles)))), titles);
            }
            @Override public List<? extends batch.WorkUnit<Map<String, Acquired>>> split() {
                if (titles.size() < 2) return List.of();
                int middle = titles.size() / 2;
                return List.of(unit(titles.subList(0, middle), fetcher),
                        unit(titles.subList(middle, titles.size()), fetcher));
            }
        };
    }

    private static Map<String, Acquired> parse(JsonNode root, List<String> requested) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (JsonNode value : root.path("query").path("normalized")) {
            aliases.put(value.path("from").asText(""), value.path("to").asText(""));
        }
        for (JsonNode value : root.path("query").path("redirects")) {
            aliases.put(value.path("from").asText(""), value.path("to").asText(""));
        }
        Map<String, Acquired> pages = new LinkedHashMap<>();
        for (JsonNode page : root.path("query").path("pages")) {
            if (page.path("missing").asBoolean(false)) continue;
            String title = page.path("title").asText("");
            wikipedia.WikipediaArticleClient.ParsedArticleText parsed =
                    wikipedia.WikipediaArticleClient.parseArticleText(
                            page.path("extract").asText(""));
            String text = parsed.text();
            String revision = page.path("lastrevid").asText("");
            if (revision.isBlank() && page.path("revisions").isArray()
                    && !page.path("revisions").isEmpty()) {
                revision = page.path("revisions").get(0).path("revid").asText("");
            }
            ArticleIntroduction evidence = title.isBlank() || text.isBlank()
                    ? null : new ArticleIntroduction(text,
                    wikipedia.WikipediaArticleClient.sourceDocument(
                            title, revision, text, List.of(), parsed.links()), parsed.links());
            pages.put(title, new Acquired(evidence));
        }
        Map<String, Acquired> out = new LinkedHashMap<>();
        for (String title : requested) {
            String actual = title;
            for (int i = 0; i < 4 && aliases.containsKey(actual); i++) {
                actual = aliases.get(actual);
            }
            out.put(title, pages.getOrDefault(actual, new Acquired(null)));
        }
        return out;
    }

    private static String url(List<String> titles) {
        return "https://en.wikipedia.org/w/api.php?action=query"
                + "&prop=extracts%7Crevisions&exintro=1&rvprop=ids"
                + "&redirects=1&format=json&formatversion=2&titles="
                + encode(String.join("|", titles));
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String fetchRemote(URI uri) throws Exception {
        try (java.io.InputStream in = objectview.utils.UrlOpener.open(uri.toURL())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface interface Fetcher { String fetch(URI uri) throws Exception; }

    private record Declaration(GeneratedClassModel owner, GeneratedFieldModel field,
                               SourceBindingTarget target) { }
    private record Acquired(ArticleIntroduction evidence) { }
    public record Result(int pages, int values, int batches) { }
}
