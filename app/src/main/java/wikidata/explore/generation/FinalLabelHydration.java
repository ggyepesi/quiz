package wikidata.explore.generation;

import wikidata.WikidataIds;
import wikidata.api.WikidataApiClient;
import wikidata.api.WikidataEntityLabelResolver;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataObjectGraph;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One final label pass over the semantically closed reachable graph. */
public final class FinalLabelHydration {
    public record Result(int requested, int resolved, int missing,
                         List<String> unavailableQids) { }

    private FinalLabelHydration() { }

    public static Result apply(Collection<WikidataDynamicObject> roots,
                               WikidataApiClient api, GenerationLog log,
                               GenerationQualityTracker quality) throws Exception {
        return apply(roots, api, log, quality, null, null);
    }

    public static Result apply(Collection<WikidataDynamicObject> roots,
                               WikidataApiClient api, GenerationLog log,
                               GenerationQualityTracker quality,
                               wikidata.explore.model.GeneratedProjectModel model,
                               datasource.api.SourceExecutionPlan sourcePlan) throws Exception {
        java.util.Set<String> labelClasses = sourcePlan == null ? java.util.Set.of()
                : wikidata.explore.model.ClassNameSourcePlan.labels(sourcePlan);
        Map<String, List<WikidataDynamicObject>> placeholders = new LinkedHashMap<>();
        for (WikidataDynamicObject object : WikidataObjectGraph.reachable(roots)) {
            if (object == null || !WikidataIds.isQid(object.qid())) continue;
            if (model != null && sourcePlan != null && modeledWithoutSourceLabel(
                    object, model, labelClasses)) continue;
            String name = object.getDisplayName();
            if (name == null || name.isBlank() || name.equalsIgnoreCase(object.qid())) {
                placeholders.computeIfAbsent(object.qid(), ignored -> new java.util.ArrayList<>())
                        .add(object);
            }
        }
        if (placeholders.isEmpty()) return new Result(0, 0, 0, List.of());
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        WikidataEntityLabelResolver.Result result;
        try (GenerationLog.Group group = sink.group("Hydrate final labels for "
                + placeholders.size() + " reachable entities")) {
            result = new WikidataEntityLabelResolver(api).resolve(
                    placeholders.keySet(),
                    WikidataEntityLabelResolver.Execution.BOUNDED_PARALLEL,
                    group.batchSink());
        }
        result.labels().forEach((qid, label) ->
                placeholders.getOrDefault(qid, List.of()).forEach(o -> o.name(label)));
        result.missing().forEach(qid ->
                placeholders.getOrDefault(qid, List.of()).forEach(o ->
                        o.wikidataEntityMissing(true)));
        if (quality != null && !result.unavailableQids().isEmpty()) {
            quality.failed("labels", "Entity labels unavailable", result.unavailableQids());
        }
        sink.message("Final label hydration: " + result.labels().size() + " resolved, "
                + result.missing().size() + " explicitly missing, "
                + result.unavailableQids().size() + " unavailable.\n");
        return new Result(placeholders.size(), result.labels().size(),
                result.missing().size(), result.unavailableQids());
    }

    /** Unmodelled references keep the safe QID-label fallback; a modelled class gets
     *  Wikidata labels only when its class-name binding declares them. */
    private static boolean modeledWithoutSourceLabel(
            WikidataDynamicObject object,
            wikidata.explore.model.GeneratedProjectModel model,
            java.util.Set<String> labelClasses) {
        boolean modeled = false;
        for (String className : object.directClassNames()) {
            if (model.findClass(className) == null) continue;
            modeled = true;
            if (labelClasses.contains(className)) return false;
        }
        return modeled;
    }
}
