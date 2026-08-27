package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.EntityKindRule;

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
        assertEquals("Q5",
                ModelSourceWorkbenchPanel.fieldSampleContext(model, spouse).ownerTypeQid());
    }

    @Test void aFieldSampleUsesAnEvidenceDerivedKindAsItsPopulation() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        var type = person.addField("type", FieldType.ENTITY, FieldCardinality.COLLECTION);
        type.mapping().propertyPid("P31");
        model.addClass(person);
        model.addEntityKindRule(new EntityKindRule("Person", java.util.List.of("Q5")));

        assertEquals("Q5",
                ModelSourceWorkbenchPanel.fieldSampleContext(model, type).ownerTypeQid());
    }
}
