package workbench;

import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityResultPanelTest {

    @Test void reusableEntityResultsSupportIntervalSelection() {
        EntityResultPanel panel = new EntityResultPanel(
                List.of("QID", "Label"), 0, true);
        panel.setRows(List.of(
                List.of("Q80061", "Physiology or Medicine"),
                List.of("Q38104", "Physics"),
                List.of("Q44585", "Chemistry")));

        JTable table = table(panel);
        table.setRowSelectionInterval(0, 2);

        assertEquals(3, panel.selectionCount());
        assertEquals(List.of("Q80061", "Q38104", "Q44585"),
                panel.selectedQids());
    }

    private static JTable table(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JTable found) return found;
            if (component instanceof Container child) {
                try {
                    return table(child);
                } catch (AssertionError ignored) {
                    // Continue with the next component branch.
                }
            }
        }
        throw new AssertionError("No JTable found");
    }
}
