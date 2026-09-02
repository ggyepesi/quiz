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
        boolean truncated = upstreamTruncated || values.size() > limit;
        List<WikidataDynamicObject> bounded = values.stream().limit(limit).toList();
        GenerationPipeline pipeline = new GenerationPipeline();
        try (GeneratedViewableRuntime runtime = pipeline.buildRuntime(snapshot)) {
            List<Viewable> objects = pipeline.materialize(runtime, bounded);
            GeneratedViewableRuntime.ClassRuntime selectedRuntime = runtime.forType(runtimeClass);
            return new ClassSampleResult(new ObjectQueryResult(
                    objects,
                    selectedRuntime == null ? runtime.generatedClass()
                            : selectedRuntime.generatedClass(),
                    runtime.source()), requestedClass, route, limit, truncated);
        }
    }
}
