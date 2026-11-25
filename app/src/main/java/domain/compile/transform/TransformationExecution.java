package domain.compile.transform;

import objectview.Viewable;
import domain.transform.FilterPredicates;
import domain.compile.codegen.GeneratedViewableRuntime;
import domain.runtime.WikidataDynamicObject;
import domain.build.GenerationPipeline;
import domain.build.GenerationRun;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.TransformationClassSource;
import domain.config.TransformationStep;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Executes the same saved elementary operations without TransformApp. */
public final class TransformationExecution {
    private TransformationExecution() { }

    public record Result(GenerationRun run, List<WikidataDynamicObject> population) {
        public Result {
            population = List.copyOf(population == null ? List.of() : population);
        }
    }

    public record Preview(int examined, List<WikidataDynamicObject> members) { }

    /** The input of a runnable recipe, if the class has one. */
    public static GeneratedClassModel inputClass(GeneratedProjectModel model, GeneratedClassModel output) {
        if (output == null || !output.materializedByTransformation()) return null;
        TransformationClassSource source = output.transformationSource();
        return source != null && source.configured()
                ? model.resolveClass(source.inputClassId(), source.inputClassName()) : null;
    }

    /** Evaluates the recipe against the loaded pool without changing any class stamps. */
    public static Preview preview(GeneratedProjectModel model, GeneratedClassModel output,
            Collection<? extends Viewable> pool) {
        var input = requireInputClass(model, output);
        var evaluated = evaluate(model, input.className(), output, pool);
        return new Preview(evaluated.candidates().size(), evaluated.members());
    }

    public static Result apply(GeneratedProjectModel model,
                               GeneratedClassModel output,
                               GenerationRun source) throws Exception {
        if (model == null || output == null || source == null) {
            throw new IllegalArgumentException(
                    "Model, materialized output class and source run are required");
        }
        String input = requireInputClass(model, output).className();
        List<WikidataDynamicObject> pool = PoolCopy.deepCopy(source.dynamicObjects());
        var evaluated = evaluate(model, input, output, pool);
        for (WikidataDynamicObject value : evaluated.candidates()) {
            if (value.directClassNames().contains(output.className())) {
                value.removeClass(output.className());
                if (!value.hasTypeStamp()) value.type(input);
            }
        }
        var selected = evaluated.members();
        selected.forEach(value -> value.assignSubclass(output.className(), input));

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

    private static GeneratedClassModel requireInputClass(GeneratedProjectModel model,
            GeneratedClassModel output) {
        var input = inputClass(model, output);
        if (input == null) {
            throw new IllegalArgumentException("Transformation source of '"
                    + output.className() + "' is not fully configured");
        }
        return input;
    }

    private record Evaluation(List<WikidataDynamicObject> candidates,
            List<WikidataDynamicObject> members) { }

    private static Evaluation evaluate(GeneratedProjectModel model, String input,
            GeneratedClassModel output, Collection<? extends Viewable> pool) {
        var steps = output.transformationSource().steps();

        List<WikidataDynamicObject> candidates = new ArrayList<>();
        List<WikidataDynamicObject> selected = new ArrayList<>();
        for (Viewable instance : pool) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (!(instance instanceof WikidataDynamicObject value)) continue;
            var classes = value.directClassNames();
            if (classes.contains(output.className()) || classes.stream()
                    .anyMatch(name -> model.isSameOrSubclass(name, input))) {
                candidates.add(value);
                if (matches(value, steps)) selected.add(value);
            }
        }
        return new Evaluation(candidates, List.copyOf(selected));
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
