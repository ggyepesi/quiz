package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import datasource.graph.constraint.GraphEvidenceCondition;
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
        assertNotNull(button(panel, "Apply graph"));
        assertNull(find(workbench, FieldSourcePanel.class)
                .getClientProperty("graph constraints"),
                "the graph editor is not encoded in field configuration");
    }

    @Test void configuredQidsAreReusedButApplyingIsExplicit() {
        GeneratedProjectModel model = model();
        GeneratedClassModel position = model.rootClass();
        position.seedQids().add("Q4164871");
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.refresh();

        assertEquals("1 QID", named(panel, "graph.startQids", JLabel.class).getText());

        text(panel, "graph.edgeProperty").setText("P279");
        named(panel, "graph.edgeDirection", JComboBox.class).setSelectedIndex(1);
        named(panel, "graph.targetUse", JComboBox.class).setSelectedIndex(1);
        text(panel, "graph.evidenceProperty").setText("P1001");
        button(panel, "Add evidence relation").doClick();
        text(panel, "graph.testProperty").setText("P576");
        button(panel, "Add evidence test").doClick();
        named(panel, "graph.reviewDisposition", JComboBox.class).setSelectedItem(
                GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT);

        assertNull(model.graphDiscoveryConfiguration(),
                "editing the draft must not mutate the model");
        button(panel, "Apply graph").doClick();

        assertEquals("P279", model.graphDiscoveryConfiguration().nextNodes()
                .getFirst().property().relationId());
        GraphEvidenceCondition evidence = model.graphDiscoveryConfiguration().nextNodes()
                .getFirst().evidenceCondition();
        assertEquals("P1001", evidence.evidencePaths().getFirst().relation().relationId());
        assertEquals("P576", evidence.tests().getFirst().relation().relationId());
        assertEquals(GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT,
                evidence.reviewDisposition());
    }

    @Test void aStoredGraphHasAnExplicitExecutionSeparateFromGeneration() {
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.refresh();

        assertNotNull(button(panel, "Run graph"));
        assertTrue(!button(panel, "Run graph").isEnabled(),
                "a graph cannot run before it is saved and a runner is available");

        text(panel, "graph.edgeProperty").setText("P279");
        button(panel, "Apply graph").doClick();

        assertNotNull(model.graphDiscoveryConfiguration());
        JLabel applied = named(panel, "graph.status", JLabel.class);
        assertTrue(applied.getText().contains("Generation does not use it yet"),
                applied.getText());

        panel.refresh();

        assertTrue(named(panel, "graph.status", JLabel.class).getText()
                        .contains("Generation does not use it yet"),
                "a graph read back from the model says it too");
    }

    @Test void evidenceRequiresBothTheRelationAndItsTest() {
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.refresh();
        text(panel, "graph.edgeProperty").setText("P279");
        text(panel, "graph.evidenceProperty").setText("P1001");
        button(panel, "Add evidence relation").doClick();

        button(panel, "Apply graph").doClick();

        assertNull(model.graphDiscoveryConfiguration());
        assertTrue(named(panel, "graph.status", JLabel.class).getText()
                .contains("both an evidence relation and an evidence test"));
    }

    @Test void clearingTheDraftLeavesTheSavedGraphUntilApply() {
        // Every other control here builds a draft, and the panel says so. One button
        // writing straight through to the model is the difference between a panel with
        // a commit point and one without — and it was the destructive one.
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.refresh();
        text(panel, "graph.edgeProperty").setText("P279");
        button(panel, "Apply graph").doClick();
        assertNotNull(model.graphDiscoveryConfiguration());

        button(panel, "Clear draft").doClick();

        assertNotNull(model.graphDiscoveryConfiguration(),
                "clearing the draft must not remove the saved graph on its own");
        assertTrue(named(panel, "graph.status", JLabel.class).getText()
                .contains("Apply graph to remove"));

        button(panel, "Apply graph").doClick();

        assertNull(model.graphDiscoveryConfiguration(),
                "applying an emptied draft is how the saved graph is removed");
    }

    private static GeneratedProjectModel model() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        return model;
    }

    private static JTextComponent text(Container root, String name) {
        return named(root, name, JTextComponent.class);
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
