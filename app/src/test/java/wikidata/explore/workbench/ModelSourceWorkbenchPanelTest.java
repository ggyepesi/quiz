package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelSourceWorkbenchPanelTest {

    @Test void aFieldSampleUsesItsDeclaringClassRatherThanTheProjectRoot() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel history = new GeneratedClassModel("History");
        model.addClass(history);
        model.rootClass(history);
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().sourceQid("Q5");
        var spouse = person.addField(
                "spouse", FieldType.ENTITY, FieldCardinality.AUTO);
        spouse.mapping().propertyPid("P26");
        model.addClass(person);

        assertEquals(person,
                ModelSourceWorkbenchPanel.fieldSampleContext(model, spouse).ownerClass());
    }
}
