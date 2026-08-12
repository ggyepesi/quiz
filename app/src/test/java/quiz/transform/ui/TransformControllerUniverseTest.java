package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import quiz.transform.app.SnapshotDomain;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformControllerUniverseTest {
    @Test void entityUniverseIsExplicitAndHasNoFabricatedClassSchema() {
        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "Person");
        person.type("Person");
        TransformController controller = new TransformController(
                new SnapshotDomain(List.of(person)), null);

        assertTrue(controller.types().contains(TransformController.ALL_ENTITIES));
        assertEquals(1, controller.instanceCount(TransformController.ALL_ENTITIES));
        assertTrue(controller.fields(TransformController.ALL_ENTITIES).isEmpty());
        assertTrue(controller.structuralFields(TransformController.ALL_ENTITIES).isEmpty());
        assertNull(controller.fieldSchema(TransformController.ALL_ENTITIES));
        assertNull(controller.configSample(TransformController.ALL_ENTITIES));
        assertEquals(List.of(person), controller.groupRoot(
                TransformController.ALL_ENTITIES).getMembers());
    }
}
