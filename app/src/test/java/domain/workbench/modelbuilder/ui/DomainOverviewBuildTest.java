package domain.workbench.modelbuilder.ui;

import org.junit.jupiter.api.Test;
import domain.config.BuildOperation;
import domain.config.ClassKind;
import domain.config.GeneratedProjectModel;

import javax.swing.*;
import java.awt.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project's build is authored in the project overview (directive 11: the UI is the
 * authority for configuration). Adding and moving steps writes the build at once; a step
 * that stays listed keeps the operation it was.
 */
class DomainOverviewBuildTest {

    @Test void addingAndMovingStepsWritesTheBuildInOrder() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.getOrCreateClass("PositionGraph").classKind(ClassKind.GRAPH);
        DomainOverviewPanel panel = new DomainOverviewPanel(model);
        int[] changes = { 0 };
        panel.afterChange(ignored -> changes[0]++);
        JPanel build = named(panel, JPanel.class, "project.build");
        JComboBox<?> available = find(build, JComboBox.class);

        add(build, available, BuildOperation.Kind.SAVE_PROJECT_RESULT);
        add(build, available, BuildOperation.Kind.GENERATE_PROJECT);
        String generateId = model.buildOperations().get(1).declarationId();
        find(build, JList.class).setSelectedIndex(1);
        button(build, "Up").doClick();

        assertEquals(List.of("Generate project", "Save project"),
                model.buildOperations().stream().map(op -> op.describe(model)).toList());
        assertEquals(generateId, model.buildOperations().getFirst().declarationId(),
                "a moved step is the same operation");
        assertEquals(3, changes[0]);
        assertEquals(4, available.getItemCount(), "Save is used. Generate and the two graph actions are available.");
    }

    @Test void repeatedStepsKeepTheirOwnIdsWhenMovedRemovedAndAdded() {
        var model = new GeneratedProjectModel();
        var first = new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, "");
        var repeated = first.repeat();
        model.buildOperations(List.of(first, repeated));
        var panel = new DomainOverviewPanel(model);
        var build = named(panel, JPanel.class, "project.build");
        var rows = find(build, JList.class);

        rows.setSelectedIndex(1);
        button(build, "Up").doClick();
        assertEquals(List.of(repeated.declarationId(), first.declarationId()),
                model.buildOperations().stream().map(BuildOperation::declarationId).toList());

        button(build, "Remove").doClick();
        add(build, find(build, JComboBox.class), BuildOperation.Kind.GENERATE_PROJECT);
        assertEquals(first.declarationId(), model.buildOperations().getFirst().declarationId());
        assertNotEquals(first.declarationId(), model.buildOperations().getLast().declarationId());
        assertNotEquals(repeated.declarationId(), model.buildOperations().getLast().declarationId());
    }

    @Test void onlyTheRebuildOperationsRemainAvailableForTheSameTarget() {
        var model = new GeneratedProjectModel();
        var graph = model.getOrCreateClass("PositionGraph");
        graph.classKind(ClassKind.GRAPH);
        var output = model.getOrCreateClass("SelectedPosition");
        output.populationMode(domain.config.ClassPopulationMode.MATERIALIZED_TRANSFORM);
        var build = named(new DomainOverviewPanel(model), JPanel.class, "project.build");
        var available = find(build, JComboBox.class);

        var repeatable = List.of(BuildOperation.Kind.GENERATE_PROJECT,
                BuildOperation.Kind.APPLY_GRAPH_DECISIONS, BuildOperation.Kind.RUN_TRANSFORMATION);
        for (var kind : repeatable) {
            add(build, available, kind);
            assertTrue(offered(available, kind));
        }
        for (var kind : List.of(BuildOperation.Kind.RUN_GRAPH_CONSTRAINT, BuildOperation.Kind.SAVE_PROJECT_RESULT)) {
            add(build, available, kind);
            assertFalse(offered(available, kind));
        }
    }

    @Test void existingRepeatedSavesStayEditableAndAreOfferedAgainAfterBothAreRemoved() {
        var model = new GeneratedProjectModel();
        var save = new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, "");
        var repeated = save.repeat();
        model.buildOperations(List.of(save, repeated));
        var build = named(new DomainOverviewPanel(model), JPanel.class, "project.build");
        var rows = find(build, JList.class);
        var available = find(build, JComboBox.class);
        assertFalse(offered(available, save.kind()));

        rows.setSelectedIndex(0);
        button(build, "Remove").doClick();
        assertEquals(repeated.declarationId(), model.buildOperations().getFirst().declarationId());
        assertFalse(offered(available, save.kind()));

        rows.setSelectedIndex(0);
        button(build, "Remove").doClick();
        assertTrue(offered(available, save.kind()));
    }

    private static boolean offered(JComboBox<?> available, BuildOperation.Kind kind) {
        for (int i = 0; i < available.getItemCount(); i++) {
            if (available.getItemAt(i) instanceof BuildOperation step && step.kind() == kind) return true;
        }
        return false;
    }

    private static void add(JPanel build, JComboBox<?> available, BuildOperation.Kind kind) {
        for (int i = 0; i < available.getItemCount(); i++) {
            if (available.getItemAt(i) instanceof BuildOperation step
                    && step.kind() == kind) {
                available.setSelectedIndex(i);
                button(build, "Add").doClick();
                return;
            }
        }
        throw new AssertionError("not offered: " + kind);
    }

    private static JButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) {
                JButton found = button(nested, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T named(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container nested) {
                T found = named(nested, type, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found = find(nested, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
