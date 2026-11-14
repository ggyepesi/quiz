package wikidata.explore.generation;

import datasource.api.BindingScope;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceExecutionPlan;
import datasource.pageviews.PageviewsClient;
import datasource.pageviews.PageviewsDatasourceProvider;
import datasource.pageviews.PageviewsWork;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills configured number fields with English Wikipedia article views.
 *
 * <p>The article comes from the entity's one retained Wikipedia correspondence; the
 * Pageviews API answers one article per request, so each article is one work unit. An
 * article the service has no record of in the window gets no value, never zero: absent
 * and "nobody read it" are different facts.
 */
public final class PageviewsFieldAcquisition {

    private PageviewsFieldAcquisition() { }

    public static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, GenerationLog log,
            work.CancellationToken cancellation, wikidata.api.WikidataApiClient api,
            SourceExecutionPlan plan) throws Exception {
        return apply(model, pool, log, cancellation, api, plan,
                new PageviewsClient(), Clock.systemUTC());
    }

    static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, GenerationLog log,
            work.CancellationToken cancellation, wikidata.api.WikidataApiClient api,
            SourceExecutionPlan plan, PageviewsClient client, Clock clock)
            throws Exception {
        List<Declaration> declarations = declarations(model, plan);
        if (declarations.isEmpty() || pool == null || pool.isEmpty()) {
            return new Result(0, 0);
        }
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        work.CancellationToken token = cancellation == null
                ? new work.CancellationToken() : cancellation;
        int values = 0;
        int articles = 0;
        List<String> unread = new ArrayList<>();
        // Declarations sharing a window share their requests.
        Map<Integer, List<Declaration>> byMonths = new LinkedHashMap<>();
        declarations.forEach(declaration -> byMonths.computeIfAbsent(
                declaration.months(), ignored -> new ArrayList<>()).add(declaration));
        for (Map.Entry<Integer, List<Declaration>> entry : byMonths.entrySet()) {
            PageviewsClient.Window window =
                    PageviewsClient.Window.lastCompleteMonths(entry.getKey(), clock);
            List<Declaration> members = entry.getValue();
            List<WikidataDynamicObject> targets = pool.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(object -> members.stream().anyMatch(
                            declaration -> FieldSourceTargeting.applies(model, object,
                                    declaration.owner(), declaration.target())))
                    .toList();
            Map<String, List<WikidataDynamicObject>> byTitle =
                    WikipediaArticleCorrespondence.byTitle(targets, api, sink);
            if (byTitle.isEmpty()) continue;
            Map<String, Long> views = new LinkedHashMap<>();
            try (GenerationLog.Group group = sink.group("Read English Wikipedia views, "
                    + window + " — " + byTitle.size() + " article(s), one request each")) {
                List<batch.WorkUnit<Long>> units = new ArrayList<>();
                for (String title : byTitle.keySet()) {
                    units.add(PageviewsWork.article(title, window, client));
                }
                // Best effort: one article the service keeps failing on must not cost
                // the articles after it their values. Those left unread are named below.
                List<batch.WorkDescriptor> failed = new batch.BatchExecutor<Long>(
                        batch.BatchPolicy.defaults().withResume(false),
                        group.batchProgress(), batch.FailureClassifier.standard(), token,
                        batch.BatchCheckpointStore.NONE, 1)
                        .runBestEffort(units, (descriptor, total) -> {
                            if (total != null) {
                                views.put(descriptor.parameters().get("title"), total);
                            }
                        });
                for (batch.WorkDescriptor descriptor : failed) {
                    String title = descriptor.parameters().get("title");
                    unread.add(title + " (" + byTitle.get(title).stream()
                            .map(WikidataDynamicObject::getQid)
                            .collect(java.util.stream.Collectors.joining(", ")) + ")");
                }
            }
            articles += views.size();
            for (Map.Entry<String, Long> read : views.entrySet()) {
                for (WikidataDynamicObject object : byTitle.get(read.getKey())) {
                    for (Declaration declaration : members) {
                        if (!FieldSourceTargeting.applies(model, object,
                                declaration.owner(), declaration.target())) continue;
                        object.put(declaration.field().name(), read.getValue());
                        values++;
                    }
                }
            }
        }
        sink.message("Wikimedia Pageviews: " + values + " field value(s) from "
                + articles + " article(s).\n");
        if (!unread.isEmpty()) {
            // The values already read stay; the failure makes the run partial and names
            // exactly which instances have no value because a request failed.
            throw new java.io.IOException("Wikimedia Pageviews left " + unread.size()
                    + " article(s) unread after all others finished: "
                    + String.join(", ", unread));
        }
        return new Result(articles, values);
    }

    private static List<Declaration> declarations(
            GeneratedProjectModel model, SourceExecutionPlan plan) {
        if (model == null || plan == null) return List.of();
        List<Declaration> result = new ArrayList<>();
        for (SourceExecutionPlan.Step step : plan.steps(BindingScope.FIELD_VALUE)) {
            PageviewsDatasourceProvider.ArticleViewsSpec spec = step.prepared()
                    .configuration(PageviewsDatasourceProvider.ArticleViewsSpec.class);
            if (spec == null) continue;
            GeneratedClassModel owner = model.findClass(step.target().className());
            GeneratedFieldModel field = wikidata.explore.model.OwnedFieldSources
                    .declaredField(model, step.target().className(),
                            step.target().fieldPath());
            if (owner == null || field == null) {
                throw new IllegalArgumentException("Datasource plan targets unknown field "
                        + step.target().className() + "." + step.target().fieldPath());
            }
            result.add(new Declaration(owner, field, step.target(), spec.months()));
        }
        return List.copyOf(result);
    }

    private record Declaration(GeneratedClassModel owner, GeneratedFieldModel field,
            SourceBindingTarget target, int months) { }

    public record Result(int articles, int values) { }
}
