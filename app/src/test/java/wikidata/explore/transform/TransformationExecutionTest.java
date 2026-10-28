package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import quiz.transform.FilterOperator;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.GenerationRun;
import wikidata.explore.model.ClassPopulationMode;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.TransformationClassSource;
import wikidata.explore.model.TransformationStep;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformationExecutionTest {

    @Test void filterProducesTypedObjectsWithTheirFieldsAndRewiredReferences() throws Exception {
        GeneratedProjectModel model = model();
        WikidataDynamicObject holder = object("Q2", "Person");
        WikidataDynamicObject kept = object("Q1", "Position");
        kept.put("holderCount", 3);
        kept.put("holder", holder);
        WikidataDynamicObject dropped = object("Q3", "Position");
        dropped.put("holderCount", 0);
        GenerationRun source = run(model, List.of(kept, dropped, holder));

        TransformationExecution.Result result = TransformationExecution.apply(
                model, model.findClass("PositionWithHolders"), source);

        assertEquals(List.of("Q1"), result.population().stream()
                .map(WikidataDynamicObject::getIdentifier).toList());
        WikidataDynamicObject output = result.population().getFirst();
        assertTrue(output.directClassNames().contains("PositionWithHolders"));
        assertEquals(3, output.get("holderCount"));
        WikidataDynamicObject copiedHolder = (WikidataDynamicObject) output.get("holder");
        assertSame(result.run().dynamicObjects().get(2), copiedHolder,
                "the copied population retains graph identity, not detached reference stubs");
    }

    private static GeneratedProjectModel model() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Positions");
        model.rootClass().className("Position");
        GeneratedClassModel output = new GeneratedClassModel("PositionWithHolders");
        output.baseClassName("Position");
        output.populationMode(ClassPopulationMode.MATERIALIZED_TRANSFORM);
        model.addClass(output);
        TransformationClassSource transformation = new TransformationClassSource();
        transformation.inputClassReference(model.rootClass().declarationId(), "Position");
        transformation.addStep(TransformationStep.filter(
                "holderCount", FilterOperator.GREATER_THAN, 0, null));
        output.transformationSource(transformation);
        return model;
    }

    private static GenerationRun run(
            GeneratedProjectModel model, List<WikidataDynamicObject> objects) throws Exception {
        GenerationPipeline pipeline = new GenerationPipeline();
        var runtime = pipeline.buildRuntime(model);
        return new GenerationRun(model.copy(), 1, pipeline.plan(model), objects, runtime,
                pipeline.materialize(runtime, objects));
    }

    private static WikidataDynamicObject object(String id, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(id, id);
        value.type(type);
        return value;
    }
}
