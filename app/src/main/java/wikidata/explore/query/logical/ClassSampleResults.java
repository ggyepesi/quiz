package wikidata.explore.query.logical;

import objectview.Viewable;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.result.ClassSampleResult;
import wikidata.explore.query.result.ObjectQueryResult;

import java.util.List;

/** One materialization boundary for every class-sampling production adapter. */
final class ClassSampleResults {
    private ClassSampleResults() { }

    static ClassSampleResult materialize(
            GeneratedProjectModel snapshot, String requestedClass, String runtimeClass,
            String route, int limit, List<WikidataDynamicObject> produced) throws Exception {
        return materialize(snapshot, requestedClass, runtimeClass, route, limit, produced,
                produced != null && produced.size() > limit);
    }

    static ClassSampleResult materialize(
            GeneratedProjectModel snapshot, String requestedClass, String runtimeClass,
            String route, int limit, List<WikidataDynamicObject> produced,
            boolean upstreamTruncated) throws Exception {
        List<WikidataDynamicObject> values = produced == null ? List.of() : produced;
        return show(snapshot, requestedClass, runtimeClass, route,
                values.stream().limit(limit).toList(),
                upstreamTruncated || values.size() > limit);
    }

    /**
     * Materializes exactly these objects — the caller has already decided which.
     *
     * <p>A derived class's sample shows the class in hand AND the population it was
     * produced from, so the count that matters is of the requested class and a blanket
     * cut over the whole list would take the producers as if they were instances of it.
     */
    static ClassSampleResult show(
            GeneratedProjectModel snapshot, String requestedClass, String runtimeClass,
            String route, List<WikidataDynamicObject> bounded, boolean truncated)
            throws Exception {
        return show(snapshot, requestedClass, runtimeClass, route, bounded, truncated,
                List.of());
    }

    /** @param typeOrder how the result's types relate, shown in this order */
    static ClassSampleResult show(
            GeneratedProjectModel snapshot, String requestedClass, String runtimeClass,
            String route, List<WikidataDynamicObject> bounded, boolean truncated,
            List<String> typeOrder) throws Exception {
        int limit = bounded.size();
        GenerationPipeline pipeline = new GenerationPipeline();
        try (GeneratedViewableRuntime runtime = pipeline.buildRuntime(snapshot)) {
            List<Viewable> objects = pipeline.materialize(runtime, bounded);
            GeneratedViewableRuntime.ClassRuntime selectedRuntime = runtime.forType(runtimeClass);
            return new ClassSampleResult(new ObjectQueryResult(
                    objects,
                    selectedRuntime == null ? runtime.generatedClass()
                            : selectedRuntime.generatedClass(),
                    runtime.source(), typeOrder),
                    requestedClass, route, limit, truncated);
        }
    }
}
