package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoverySavedModelTest {
    private static final File MODEL =
            new File("../data/wikidata/discovery/discovery.model.json");

    @Test void reusableDiscoveryOwnsDateAndDiscoverersAndSpecializesHumans()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(MODEL);

        assertEquals(GeneratedProjectModel.ProjectKind.MODEL, model.projectKind());
        GeneratedClassModel discovery = model.findClass("Discovery");
        assertEquals(ClassKind.OWNED, discovery.classKind());
        assertEquals("P575", field(discovery, "date").mapping().propertyPid());
        assertEquals("P61", field(discovery, "discoverers").mapping().propertyPid());
        assertEquals("Discoverer", field(discovery, "discoverers").entityClassName());
        assertEquals("Person", model.findClass("Person").importedFrom());
        EntityRepresentationRule representation = model.entityRepresentationRules()
                .stream().filter(rule -> rule.roleClassName().equals("Discoverer"))
                .findFirst().orElseThrow();
        assertEquals("Person", representation.representationClassName());
        assertNotNull(ClassSourceBindings.articleCorrespondence(
                model.findClass("Discoverer")));
        assertTrue(GeneratedProjectModelValidator.validate(model).valid());
    }

    private static GeneratedFieldModel field(
            GeneratedClassModel owner, String name) {
        return owner.fields().stream().filter(field -> field.name().equals(name))
                .findFirst().orElseThrow();
    }
}
