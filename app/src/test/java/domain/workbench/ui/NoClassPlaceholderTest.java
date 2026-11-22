package domain.workbench.ui;

import org.junit.jupiter.api.Test;
import domain.config.FieldCardinality;
import domain.config.FieldDefinition;
import domain.config.FieldRenderMode;
import domain.datasource.schema.FieldType;

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
