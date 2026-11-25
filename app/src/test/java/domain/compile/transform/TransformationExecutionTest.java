package domain.compile.transform;

import org.junit.jupiter.api.Test;
import domain.transform.FilterOperator;
import domain.runtime.WikidataDynamicObject;
import domain.build.GenerationPipeline;
import domain.build.GenerationRun;
import domain.config.ClassPopulationMode;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.TransformationClassSource;
import domain.config.TransformationStep;

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

    @Test void previewAndBuildMatchTheSameMembersWithoutRestampingLoadedInstances() throws Exception {
        var model = model();
        var output = model.findClass("PositionWithHolders");
        var formerMember = object("Q1", output.className());
        formerMember.put("holderCount", 0);
        var candidate = object("Q2", "Position");
        candidate.put("holderCount", 3);
        var otherType = object("Q3", "Person");
        otherType.put("holderCount", 3);
        var source = run(model, List.of(formerMember, candidate, otherType));

        var preview = TransformationExecution.preview(model, output, source.dynamicObjects());
        var built = TransformationExecution.apply(model, output, source);

        assertEquals(2, preview.examined());
        assertEquals(List.of(candidate), preview.members());
        assertEquals(preview.members().stream().map(WikidataDynamicObject::getIdentifier).toList(),
                built.population().stream().map(WikidataDynamicObject::getIdentifier).toList());
        assertEquals("PositionWithHolders", formerMember.typeName());
        assertEquals("Position", candidate.typeName());
        assertEquals("Position", built.run().dynamicObjects().getFirst().typeName());
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
