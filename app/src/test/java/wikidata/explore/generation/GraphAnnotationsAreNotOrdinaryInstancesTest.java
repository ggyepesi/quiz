package wikidata.explore.generation;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A graph constraint's annotations live in the project's own pool once applied, and are
 * rendered by the graph tab that knows their decision schema. Materializing them as
 * ordinary generated instances puts a second, poorer view of the same records beside the
 * classes they annotate — a tab of labels carrying none of the annotation structure.
 */
class GraphAnnotationsAreNotOrdinaryInstancesTest {

    @Test void materializeSkipsTheClassesDeclaredAsGraphConstraints() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.addField("superClasses", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("Position");
        project.rootClass(position);
        GeneratedClassModel validity = new GeneratedClassModel("PositionValidity");
        validity.classKind(ClassKind.GRAPH);
        project.addClass(validity);

        WikidataDynamicObject office = object("Q1", "Position");
        office.put("superClasses", List.of());
        WikidataDynamicObject annotation = object("Q1-decision", "PositionValidity");

        GenerationPipeline pipeline = new GenerationPipeline();
        try (GeneratedViewableRuntime runtime = pipeline.buildRuntime(project)) {
            List<objectview.Viewable> instances =
                    pipeline.materialize(runtime, List.of(office, annotation));

            assertEquals(1, instances.size(),
                    "the annotation is not a generated instance of the project");
            assertEquals("Position", instances.getFirst().getClass().getSimpleName());
            assertTrue(instances.stream().noneMatch(instance ->
                            "PositionValidity".equals(
                                    instance.getClass().getSimpleName())),
                    "graph annotations belong to their graph tab, not the ordinary "
                            + "instances list");
        }
    }

    private static WikidataDynamicObject object(String id, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(id, id);
        value.type(type);
        value.typeKey(type);
        return value;
    }
}
