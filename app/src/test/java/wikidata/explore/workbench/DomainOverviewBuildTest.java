package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.BuildOperation;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.*;
import java.awt.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The project's build is authored in the project overview (directive 10: the UI is the
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
        assertEquals(3, available.getItemCount(),
                "nothing chosen, then run and apply for the one graph");
    }

    private static void add(JPanel build, JComboBox<?> available, BuildOperation.Kind kind) {
        for (int i = 0; i < available.getItemCount(); i++) {
            if (available.getItemAt(i) instanceof DomainOverviewPanel.Step step
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
