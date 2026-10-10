package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstellationsSavedModelTest {
    private static final File MODEL =
            new File("../data/wikidata/constellations/constellations.model.json");

    @Test void constellationUsesReusableDiscoveryAndArticleCorrespondence()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(MODEL);
        GeneratedFieldModel discovery = model.findClass("Constellation").fields().stream()
                .filter(field -> field.name().equals("discovery"))
                .findFirst().orElseThrow();

        assertEquals("Discovery", discovery.entityClassName());
        assertEquals(FieldProductionKind.OWNED_COMPONENT,
                discovery.mapping().productionKind());
        org.junit.jupiter.api.Assertions.assertNull(
                model.findClass("ConstellationDiscovery"));
        assertEquals("Discovery", model.findClass("Discovery").importedFrom());
        assertEquals("Astronomy", model.findClass("CelestialBody").importedFrom());
        assertEquals("Astronomy", model.findClass("Star").importedFrom());
        assertEquals("CelestialBody", model.findClass("Star").baseClassName());
        assertNotNull(ClassSourceBindings.articleCorrespondence(
                model.findClass("Constellation")));
        assertTrue(ArticleCorrespondencePlan.classes(model,
                ModelSourceExecutionPlan.compile(model, datasource.Datasources.standard()))
                .contains("Star"));
        assertTrue(GeneratedProjectModelValidator.validate(model).valid());
    }
}
