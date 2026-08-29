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

    @Test void pastedQidsGrowTheChosenVocabularyAndNonQidsAreReported() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addSelection(new VocabularySelection("Prize"));
        SelectionViewerPanel panel = new SelectionViewerPanel(project, null, null);
        panel.refreshSelections();

        panel.addPastedQids("Q80061 Q38104, Q44585\nQ35637 not-a-qid Q38104");

        VocabularySelection prize = (VocabularySelection) project.findSelection("Prize");
        assertEquals(java.util.List.of("Q80061", "Q38104", "Q44585", "Q35637"),
                prize.valueQids(), "in paste order, never twice");
        assertTrue(status(panel).contains("1 not a QID"), "rejects are reported");
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

    private static String status(Container root) {
        StringBuilder all = new StringBuilder();
        collectLabels(root, all);
        return all.toString();
    }

    private static void collectLabels(Container root, StringBuilder into) {
        for (Component component : root.getComponents()) {
            if (component instanceof javax.swing.JLabel label) into.append(label.getText()).append('\n');
            if (component instanceof Container child) collectLabels(child, into);
        }
    }
}
