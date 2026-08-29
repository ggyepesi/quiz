package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.VocabularySelection;
import workbench.SelectionsButton;
import workbench.WorkbenchSelections;

import javax.swing.*;
import java.awt.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VocabularyFromSelectionsTest {

    @Test void reusableEntitiesAreAddedToTheChosenVocabulary() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        VocabularySelection prize = new VocabularySelection("Prize");
        prize.valueQids(List.of("Q38104"));
        project.addSelection(prize);
        WorkbenchSelections reusable = new WorkbenchSelections();
        reusable.entity("Q38104", "Physics");
        reusable.entity("Q44585", "Chemistry");

        SelectionViewerPanel panel = new SelectionViewerPanel(project, null, null);
        panel.selections(reusable);
        SelectionsButton open = component(panel, SelectionsButton.class);
        JTabbedPane tabs = (JTabbedPane) open.dialogContent();
        @SuppressWarnings("unchecked") JList<WorkbenchSelections.Entity> list =
                (JList<WorkbenchSelections.Entity>) component(
                        (Container) tabs.getComponentAt(0), JList.class);
        list.setSelectionInterval(0, 1);
        button((Container) tabs.getComponentAt(0), "Add selected entities").doClick();

        assertEquals(List.of("Q38104", "Q44585"), prize.valueQids());
    }

    @Test void theOneEntityListRemovesOnlyItsSelectedRows() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        VocabularySelection prize = new VocabularySelection("Prize");
        prize.valueQids(List.of("Q38104", "Q44585", "Q80061"));
        project.addSelection(prize);
        SelectionViewerPanel panel = new SelectionViewerPanel(project, null, null);

        JList<?> list = component(panel, JList.class);
        assertEquals(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION, list.getSelectionMode());
        list.setSelectedIndices(new int[]{0, 2});
        button(panel, "Remove selected").doClick();

        assertEquals(List.of("Q44585"), prize.valueQids());
    }

    @Test void renameUpdatesFieldsThatReferToTheVocabularyAndDeleteRefusesWhileReferenced() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        VocabularySelection prize = new VocabularySelection("Prize");
        project.addSelection(prize);
        wikidata.explore.model.GeneratedClassModel owner =
                new wikidata.explore.model.GeneratedClassModel("Award");
        wikidata.explore.model.GeneratedFieldModel field =
                new wikidata.explore.model.GeneratedFieldModel();
        field.entityClassName("Prize");
        owner.fields().add(field);
        project.addClass(owner);

        assertTrue(project.renameSelection("Prize", "NobelCategory"));
        assertEquals("NobelCategory", field.entityClassName());
        assertTrue(!project.removeSelection("NobelCategory"));
    }

    private static JButton button(Container root, String text) {
        for (Component candidate : root.getComponents()) {
            if (candidate instanceof JButton button && text.equals(button.getText())) return button;
            if (candidate instanceof Container child) {
                JButton found = buttonOrNull(child, text);
                if (found != null) return found;
            }
        }
        throw new AssertionError("No button " + text);
    }
    private static JButton buttonOrNull(Container root, String text) {
        try { return button(root, text); } catch (AssertionError ignored) { return null; }
    }
    private static <T extends Component> T component(Container root, Class<T> type) {
        for (Component candidate : root.getComponents()) {
            if (type.isInstance(candidate)) return type.cast(candidate);
            if (candidate instanceof Container child) {
                T found = componentOrNull(child, type);
                if (found != null) return found;
            }
        }
        throw new AssertionError("No " + type.getSimpleName());
    }
    private static <T extends Component> T componentOrNull(Container root, Class<T> type) {
        try { return component(root, type); } catch (AssertionError ignored) { return null; }
    }

    /**
     * A vocabulary stores QIDs only. Entities picked BY LABEL in Explore must not redraw
     * as bare QIDs the moment they are added — that is the one thing the reader is here
     * to check.
     */
    @Test void anAddedEntityKeepsTheLabelItWasPickedBy() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addSelection(new VocabularySelection("Prize"));
        WorkbenchSelections selections = new WorkbenchSelections();
        SelectionViewerPanel panel = new SelectionViewerPanel(project, null, null);
        panel.selections(selections);

        selections.entity("Q38104", "Nobel Prize in Physics");
        SelectionsButton open = component(panel, SelectionsButton.class);
        JTabbedPane tabs = (JTabbedPane) open.dialogContent();
        Container entityTab = (Container) tabs.getComponentAt(0);
        component(entityTab, JList.class).setSelectionInterval(0, 0);
        button(entityTab, "Add selected entities").doClick();

        JList<?> shown = component(panel, JList.class);
        assertEquals(1, shown.getModel().getSize());
        assertTrue(shown.getModel().getElementAt(0).toString().contains("Nobel Prize in Physics"),
                "the label survives into the vocabulary view");
    }

    /**
     * The selector holds Selections, so what it points at survives a rebuild, a reorder
     * and a rename — none of which the model promises to keep stable. Deleting what it
     * points at is the one case with no object to return to, and it falls back.
     */
    @Test void theSelectorPointsAtASelectionRatherThanAPositionOrAName() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        VocabularySelection categories = new VocabularySelection("Categories");
        VocabularySelection laureates = new VocabularySelection("Laureates");
        project.addSelection(categories);
        project.addSelection(laureates);
        SelectionViewerPanel panel = new SelectionViewerPanel(project, null, null);
        JComboBox<?> selector = component(panel, JComboBox.class);

        selector.setSelectedItem(laureates);
        project.removeSelection("Categories");
        project.addSelection(categories);
        panel.refreshSelections();
        assertSame(laureates, selector.getSelectedItem(), "a reorder does not move it");

        assertTrue(project.renameSelection("Laureates", "Laureate"));
        panel.refreshSelections();
        assertSame(laureates, selector.getSelectedItem(), "a rename does not lose it");

        project.removeSelection("Laureate");
        panel.refreshSelections();
        assertSame(categories, selector.getSelectedItem(),
                "with nothing to return to, the first selection is chosen");
    }
}
