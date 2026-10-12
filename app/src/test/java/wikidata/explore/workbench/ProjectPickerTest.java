package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;

import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One project is open even though Domain and Model are two ways to open it.
 *
 * <p>The old independent combo boxes retained two selections.  After Historical
 * Positions was open and History was selected, the tree said History while the project
 * header could still show Periodic Table.  They also shared one crowded row with three
 * buttons, leaving the Model selector rendered as an ellipsis.  These tests reproduce
 * that transition and require one usable row per selector.
 */
class ProjectPickerTest {

    private static final List<String> DOMAINS =
            List.of("Periodic Table", "History");
    private static final List<String> MODELS =
            List.of("Historical Positions", "Astronomy");

    @Test
    void switchingFromAModelToHistoryLeavesOnlyHistorySelected() {
        ProjectPicker picker = new ProjectPicker();
        picker.showProjects(
                DOMAINS, MODELS, "Historical Positions", true);

        assertNull(picker.domainSelector().getSelectedItem());
        assertEquals("Historical Positions",
                picker.modelSelector().getSelectedItem());

        picker.showProjects(DOMAINS, MODELS, "History", false);

        assertEquals("History", picker.domainSelector().getSelectedItem());
        assertNull(picker.modelSelector().getSelectedItem(),
                "an inactive picker is an open action, not a second open project");
    }

    @Test
    void theUserSelectionEventCannotRestoreTheFirstDomainAfterTheSwitch()
            throws Exception {
        ProjectPicker picker = new ProjectPicker();
        picker.showProjects(
                DOMAINS, MODELS, "Historical Positions", true);
        picker.onOpen(name -> picker.showProjects(DOMAINS, MODELS, name, false));

        picker.domainSelector().setSelectedItem("History");
        drainSwingEvents();

        assertEquals("History", picker.domainSelector().getSelectedItem());
        assertNull(picker.modelSelector().getSelectedItem());
    }

    @Test
    void eitherPickerOpensItsExplicitSelection() throws Exception {
        ProjectPicker picker = new ProjectPicker();
        List<String> opened = new ArrayList<>();
        picker.onOpen(opened::add);
        picker.showProjects(DOMAINS, MODELS, "History", false);

        picker.modelSelector().setSelectedItem("Historical Positions");
        assertTrue(opened.isEmpty(),
                "the popup gesture must finish before its combo model is rebuilt");
        drainSwingEvents();

        assertEquals(List.of("Historical Positions"), opened);
    }

    @Test
    void eachSelectorGetsTheWidthOfItsOwnRow() {
        ProjectPicker picker = new ProjectPicker();
        picker.showProjects(DOMAINS, MODELS, "History", false);
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(picker);
        header.setSize(420, 64);
        header.doLayout();
        picker.doLayout();

        assertEquals(420, picker.getWidth(),
                "the picker should fill the ModelBuilder header");
        for (java.awt.Component component : picker.getComponents()) {
            JPanel row = (JPanel) component;
            row.doLayout();
            JComboBox<?> selector = null;
            for (java.awt.Component child : row.getComponents()) {
                if (child instanceof JComboBox<?> combo) selector = combo;
            }
            assertTrue(selector != null && selector.getWidth() >= 300,
                    "the selector should use its row instead of sharing it with the other "
                            + "selector and the project-action buttons");
        }
    }

    private static void drainSwingEvents() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }
}
