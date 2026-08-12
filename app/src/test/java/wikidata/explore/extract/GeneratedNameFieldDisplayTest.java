package wikidata.explore.extract;

import objectview.field.FieldRef;
import objectview.field.FieldRole;
import objectview.field.FieldSchema;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A generated model's name field IS the class's display, so a generated snapshot must
 * carry it as DISPLAY — otherwise TransformApp shows the synthetic "Display label" for a
 * generated domain (Constellations) while a re-exported reflection domain (countries) shows
 * its real name. This makes the two paths agree.
 */
class GeneratedNameFieldDisplayTest {

    private static FieldRole roleOf(FieldSchema schema, String field) {
        return schema.fields().stream()
                .filter(f -> f.name().equals(field))
                .findFirst().map(FieldRef::role).orElse(null);
    }

    @Test void generatedNameFieldIsDeclaredAsDisplay() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel star = new GeneratedClassModel("Star");
        star.fields().add(GeneratedFieldModel.nameField());
        star.addField("apparentMagnitude", FieldType.STRING, FieldCardinality.SINGLE);
        project.addClass(star);

        SnapshotFieldGraph.Builder builder = SnapshotFieldGraph.builder();
        builder.declare(project);
        SnapshotFieldGraph graph = builder.build();
        FieldSchema schema = graph.fieldSchema("Star", Set.of());

        assertEquals(FieldRole.DISPLAY, roleOf(schema, "name"),
                "the model's name field must be the DISPLAY field");
        assertEquals(FieldRole.NONE, roleOf(schema, "apparentMagnitude"),
                "an ordinary field is not the display");
    }

    @Test void generatedDeclarationCannotLeakInternalTypesIntoTheFieldGraph() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel internal = new GeneratedClassModel("__subject_Nomination");
        project.addClass(internal);
        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        nominee.baseClassName("__subject_Nomination");
        nominee.addField("plumbing", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("__subject_Nomination");
        project.addClass(nominee);

        SnapshotFieldGraph.Builder builder = SnapshotFieldGraph.builder();
        builder.declare(project);
        SnapshotFieldGraph graph = builder.build();

        assertFalse(graph.types.containsKey("__subject_Nomination"));
        assertNull(graph.types.get("Nominee").baseType,
                "an internal base name must not survive as a second leak path");
    }
}
