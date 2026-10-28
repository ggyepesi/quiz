package wikidata.explore.transform;

import objectview.Viewable;
import quiz.transform.FilterPredicates;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.GenerationRun;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.TransformationClassSource;
import wikidata.explore.model.TransformationStep;

import java.util.ArrayList;
import java.util.List;

/** Executes the same saved elementary operations without TransformApp. */
public final class TransformationExecution {
    private TransformationExecution() { }

    public record Result(GenerationRun run, List<WikidataDynamicObject> population) {
        public Result {
            population = List.copyOf(population == null ? List.of() : population);
        }
    }

    public static Result apply(GeneratedProjectModel model,
                               GeneratedClassModel output,
                               GenerationRun source) throws Exception {
        if (model == null || output == null || source == null) {
            throw new IllegalArgumentException(
                    "Model, materialized output class and source run are required");
        }
        TransformationClassSource transformation = output.transformationSource();
        if (!output.materializedByTransformation() || transformation == null
                || !transformation.configured()) {
            throw new IllegalArgumentException("Transformation source of '"
                    + output.className() + "' is not fully configured");
        }
        GeneratedClassModel input = model.resolveClass(
                transformation.inputClassId(), transformation.inputClassName());
        if (input == null) {
            throw new IllegalArgumentException("Transformation source of '"
                    + output.className() + "' is not fully configured");
        }

        List<WikidataDynamicObject> pool = PoolCopy.deepCopy(source.dynamicObjects());
        List<WikidataDynamicObject> candidates = new ArrayList<>();
        for (WikidataDynamicObject value : pool) {
            if (value == null) continue;
            boolean wasOutput = value.directClassNames().contains(output.className());
            boolean isInput = wasOutput || value.directClassNames().stream()
                    .anyMatch(name -> model.isSameOrSubclass(name, input.className()));
            if (!isInput) continue;
            if (wasOutput) {
                value.removeClass(output.className());
                if (value.directClassNames().isEmpty()) value.type(input.className());
            }
            candidates.add(value);
        }

        List<WikidataDynamicObject> selected = candidates.stream()
                .filter(value -> matches(value, transformation.steps())).toList();
        selected.forEach(value -> value.assignSubclass(
                output.className(), input.className()));

        GeneratedProjectModel snapshot = model.copy();
        GenerationPipeline pipeline = new GenerationPipeline();
        GeneratedViewableRuntime runtime = pipeline.buildRuntime(snapshot);
        List<Viewable> instances = pipeline.materialize(runtime, pool);
        GenerationRun transformed = new GenerationRun(snapshot, source.depth(),
                pipeline.plan(snapshot), pool, runtime, instances, source.remapState(),
                source.loadedDeclarations(), source.quality(), source.fieldCoverage(),
                source.selfReferenceAudit(), source.ownedCompositionAudit(),
                source.kindClassificationAudit(), source.projectionAudit())
                .producedLike(source);
        return new Result(transformed, selected);
    }

    private static boolean matches(
            WikidataDynamicObject value, List<TransformationStep> steps) {
        for (TransformationStep step : steps) {
            if (step.kind() != TransformationStep.Kind.FILTER
                    || !FilterPredicates.matches(value, step.fieldPath(), step.operator(),
                            step.value(), step.value2())) {
                return false;
            }
        }
        return true;
    }
}
