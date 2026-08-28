package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.StatementClassSource;

import javax.swing.JComboBox;
import java.awt.Component;
import java.awt.Container;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelSourceWorkbenchPanelTest {

    @Test void editsSurviveNavigatingToAnotherModelNode() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(new StatementClassSource("P39"));
        holding.addField("position", FieldType.ENTITY, FieldCardinality.SINGLE);
        model.addClass(holding);
        model.rootClass(holding);
        GeneratedClassModel person = new GeneratedClassModel("Person");
        model.addClass(person);

        ModelSourceWorkbenchPanel panel = new ModelSourceWorkbenchPanel(model);
        panel.edit(holding);
        comboContaining(panel, "position").setSelectedItem("position");
        panel.changeSelection(person);

        assertEquals(CanonicalSpec.DisplayNameMode.FIELD,
                holding.canonical().displayNameMode());
        assertEquals("position", holding.canonical().displayNameField());
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<String> comboContaining(Container root, String item) {
        for (Component component : root.getComponents()) {
            if (component instanceof JComboBox<?> combo) {
                for (int i = 0; i < combo.getItemCount(); i++) {
                    if (item.equals(combo.getItemAt(i))) {
                        return (JComboBox<String>) combo;
                    }
                }
            }
            if (component instanceof Container child) {
                try {
                    return comboContaining(child, item);
                } catch (AssertionError ignored) {
                    // Continue with the next branch of the component tree.
                }
            }
        }
        throw new AssertionError("No combo contains " + item);
    }

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
