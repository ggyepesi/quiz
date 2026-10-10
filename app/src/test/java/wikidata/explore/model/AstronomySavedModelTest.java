package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AstronomySavedModelTest {
    private static final File MODEL =
            new File("../data/wikidata/astronomy/astronomy.model.json");

    @Test void celestialBodyAndStarAreReusableSchemas() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(MODEL);

        assertEquals(GeneratedProjectModel.ProjectKind.MODEL, model.projectKind());
        GeneratedClassModel body = model.findClass("CelestialBody");
        GeneratedClassModel star = model.findClass("Star");
        assertNotNull(body);
        assertNotNull(star);
        assertEquals("CelestialBody", star.baseClassName());
        assertEquals("Discovery", field(body, "discovery").entityClassName());
        assertEquals("P18", field(body, "image").mapping().propertyPid());
        assertEquals("P2120", field(body, "radius").mapping().propertyPid());
        assertEquals("P2386", field(body, "diameter").mapping().propertyPid());
        assertEquals("P1215", field(star, "apparentMagnitude")
                .mapping().propertyPid());
        assertEquals("Discovery", model.findClass("Discovery").importedFrom());
        assertNotNull(ClassSourceBindings.articleCorrespondence(body));
        assertTrue(GeneratedProjectModelValidator.validate(model).valid());
    }

    private static GeneratedFieldModel field(
            GeneratedClassModel owner, String name) {
        return owner.fields().stream().filter(field -> field.name().equals(name))
                .findFirst().orElseThrow();
    }
}
