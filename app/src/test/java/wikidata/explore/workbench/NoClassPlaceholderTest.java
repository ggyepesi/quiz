package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldDefinition;
import wikidata.explore.model.FieldRenderMode;
import wikidata.explore.model.FieldType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "(none)" prompts for a class; read back as one, it BECAME the class name, and the
 *  model held a reference to a class called "(none)" that nothing could satisfy. */
class NoClassPlaceholderTest {

    @Test void theNoClassPlaceholderIsNeverStoredAsAClassName() {
        FieldDefinitionPanel panel = new FieldDefinitionPanel();
        panel.edit(new FieldDefinition("familyName", FieldType.ENTITY,
                FieldDefinitionPanel.NO_CLASS, FieldCardinality.COLLECTION,
                FieldRenderMode.AUTO));

        assertEquals("", panel.definition().entityClassName());
        assertTrue(panel.validationError().contains("no class"),
                "and the field is unconfigured until a class is chosen or 'no class' ticked: "
                        + panel.validationError());
    }
}
