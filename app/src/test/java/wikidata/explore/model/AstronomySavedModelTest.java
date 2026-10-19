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
        GeneratedFieldModel magnitude = field(star, "apparentMagnitude");
        assertEquals("P1215", magnitude.mapping().propertyPid());
        assertEquals("mag", magnitude.unit());
        assertEquals("P528", star.membership().relationPid());
        assertTrue(star.membership().qids().isEmpty());
        assertEquals(new wikidata.QualifierCondition("P972", java.util.List.of("Q499138")),
                star.membership().qualifierCondition());
        assertEquals("Discovery", model.findClass("Discovery").importedFrom());
        EntityRepresentationRule owner = model.entityRepresentationRules().stream()
                .filter(rule -> rule.roleClassName().equals("Owner"))
                .findFirst().orElseThrow();
        assertEquals("CelestialBody", owner.representationClassName());
        assertNotNull(ClassSourceBindings.articleCorrespondence(body));
        assertTrue(GeneratedProjectModelValidator.validate(model).valid());
    }

    private static GeneratedFieldModel field(
            GeneratedClassModel owner, String name) {
        return owner.fields().stream().filter(field -> field.name().equals(name))
                .findFirst().orElseThrow();
    }
}
