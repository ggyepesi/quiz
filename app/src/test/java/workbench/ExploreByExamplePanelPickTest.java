package workbench;

import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JTable;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The picker emits the visibly-selected candidate, not a stale explored entity. */
class ExploreByExamplePanelPickTest {

    @Test void selectedCandidateWinsOverExploredEntity() {
        // Explored A, but candidate B is selected → B must win (the bug emitted A).
        assertArrayEquals(new String[]{"Q2", "B"},
                ExploreByExamplePanel.pickEntity(true, "Q2", "B", "Q1", "A"));
    }

    @Test void exploredEntityIsTheFallbackWithoutACandidate() {
        assertArrayEquals(new String[]{"Q1", "A"},
                ExploreByExamplePanel.pickEntity(false, "", "", "Q1", "A"));
    }

    @Test void anInvalidCandidateFallsBackToTheExploredEntity() {
        assertArrayEquals(new String[]{"Q1", "A"},
                ExploreByExamplePanel.pickEntity(true, "", "", "Q1", "A"));
    }

    @Test void nothingSelectedNorExplored() {
        assertNull(ExploreByExamplePanel.pickEntity(false, "", "", "", ""));
    }

    @Test void allHighlightedEntitiesCanBeAddedForReuse() {
        ExploreByExamplePanel panel = new ExploreByExamplePanel();
        WorkbenchSelections selections = new WorkbenchSelections();
        panel.selections(selections);
        EntityResultPanel results = component(panel, EntityResultPanel.class);
        results.setRows(List.of(
                List.of("Q80061", "Physiology or Medicine", ""),
                List.of("Q38104", "Physics", ""),
                List.of("Q44585", "Chemistry", "")));
        JTable table = component(results, JTable.class);
        table.setRowSelectionInterval(0, 2);

        SelectionsButton button = component(panel, SelectionsButton.class);
        menuItem(button, "Add selected entities to reusable selections").doClick();

        assertEquals(List.of("Q80061", "Q38104", "Q44585"),
                selections.entities().stream()
                        .map(WorkbenchSelections.Entity::qid).toList());
    }

    private static JMenuItem menuItem(SelectionsButton button, String text) {
        for (Component component : button.menu().getComponents()) {
            if (component instanceof JMenuItem item && text.equals(item.getText())) return item;
        }
        throw new AssertionError("Missing menu item: " + text);
    }

    private static <T extends Component> T component(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) return type.cast(component);
            if (component instanceof Container child) {
                try {
                    return component(child, type);
                } catch (AssertionError ignored) {
                    // Continue with the next component branch.
                }
            }
        }
        throw new AssertionError("Missing component " + type.getSimpleName());
    }

}
