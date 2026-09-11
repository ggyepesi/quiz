package wikidata.explore.workbench;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphConstraintsPanelTest {

    @Test void graphConstraintIsASeparateConfigurationSection() {
        GeneratedProjectModel model = model();
        ModelSourceWorkbenchPanel workbench = new ModelSourceWorkbenchPanel(model);

        workbench.edit(SingleRootClassModelPanel.ConfigurationSection.GRAPH_CONSTRAINTS);

        GraphConstraintsPanel panel = find(workbench, GraphConstraintsPanel.class);
        assertTrue(panel.isVisible());
        assertNotNull(button(panel, "Apply graph constraint"));
        assertNull(find(workbench, FieldSourcePanel.class)
                .getClientProperty("graph constraints"),
                "the graph editor is not encoded in field configuration");
    }

    @Test void configuredFieldsAreOfferedButApplyingIsExplicit() {
        GeneratedProjectModel model = model();
        GeneratedClassModel position = model.rootClass();
        var jurisdiction = position.addField(
                "jurisdiction", FieldType.ENTITY, FieldCardinality.COLLECTION);
        jurisdiction.mapping().propertyPid("P1001");
        jurisdiction.mapping().propertyLabel("jurisdiction");
        jurisdiction.mapping().direction(wikidata.explore.model.RuleDirection.ROOT_TO_ITEM);
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.refresh();

        JComboBox<?> offered = named(panel, "graph.evidenceField", JComboBox.class);
        assertTrue(items(offered).contains("Position.jurisdiction — jurisdiction (P1001)"));

        text(panel, "graph.conditionName").setText("historical polity");
        text(panel, "graph.evidenceProperty").setText("P1001");
        button(panel, "Add evidence edge").doClick();
        text(panel, "graph.testProperty").setText("P576");
        button(panel, "Add test").doClick();

        assertNull(position.graphAdmissionCondition(),
                "editing the draft must not mutate the model");
        button(panel, "Apply graph constraint").doClick();

        assertEquals("historical polity", position.graphAdmissionCondition().name());
        assertEquals("P1001", position.graphAdmissionCondition()
                .evidencePaths().getFirst().relation().relationId());
        assertEquals("P576", position.graphAdmissionCondition()
                .tests().getFirst().relation().relationId());
    }

    @Test void aStoredConditionSaysGenerationDoesNotApplyItYet() {
        // A constraint that is saved but not yet read by generation looks exactly like
        // one that is enforced: configure it, regenerate, and the population does not
        // move. Until #184 wires the adapter into population assembly, the panel has to
        // be the thing that says so — on BOTH lines that report a stored condition,
        // because the one shown after a reload is the one a modeller sees later.
        GeneratedProjectModel model = model();
        GeneratedClassModel position = model.rootClass();
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.refresh();

        text(panel, "graph.conditionName").setText("historical polity");
        text(panel, "graph.evidenceProperty").setText("P1001");
        button(panel, "Add evidence edge").doClick();
        text(panel, "graph.testProperty").setText("P576");
        button(panel, "Add test").doClick();
        button(panel, "Apply graph constraint").doClick();

        assertNotNull(position.graphAdmissionCondition());
        JLabel applied = named(panel, "graph.status", JLabel.class);
        assertTrue(applied.getText().contains("generation does not apply it yet"),
                applied.getText());

        panel.refresh();

        assertTrue(named(panel, "graph.status", JLabel.class).getText()
                        .contains("generation does not apply it yet"),
                "a condition read back from the model says it too");
    }

    private static GeneratedProjectModel model() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        return model;
    }

    private static JTextComponent text(Container root, String name) {
        return named(root, name, JTextComponent.class);
    }

    private static java.util.List<String> items(JComboBox<?> box) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < box.getItemCount(); i++) {
            result.add(String.valueOf(box.getItemAt(i)));
        }
        return result;
    }

    private static JButton button(Container root, String label) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && label.equals(button.getText())) return button;
            if (child instanceof Container nested) {
                JButton found = button(nested, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container nested) {
                try { return named(nested, name, type); }
                catch (AssertionError ignored) { }
            }
        }
        throw new AssertionError("No " + type.getSimpleName() + " named " + name);
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found = findOrNull(nested, type);
                if (found != null) return found;
            }
        }
        throw new AssertionError("No " + type.getSimpleName());
    }

    private static <T extends Component> T findOrNull(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found = findOrNull(nested, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
