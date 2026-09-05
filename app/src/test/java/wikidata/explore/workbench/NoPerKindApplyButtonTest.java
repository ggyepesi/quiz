package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.AggregateClassSource;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.JButton;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No kind editor has an Apply button.
 *
 * <p>Three did, with three different names, one of them mid-panel above rows it did not
 * appear to cover — and the statement editor had none and worked, because {@code
 * ModelSourceWorkbenchPanel.applyEdits} already flushes the editor that owns the
 * selected class before every save, generation, preview and selection change. The
 * buttons did not make edits take effect; they made it look as though edits would not
 * take effect without them.
 */
class NoPerKindApplyButtonTest {

    @Test void noKindEditorOffersOne() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel source = new GeneratedClassModel("Person");
        GeneratedClassModel statement = new GeneratedClassModel("Award");
        GeneratedClassModel owned = new GeneratedClassModel("Name");
        owned.ownedClass(true);
        GeneratedClassModel aggregate = new GeneratedClassModel("NobelPrize");
        aggregate.aggregateSource(new AggregateClassSource("Award", "awards"));
        for (GeneratedClassModel clazz : List.of(source, statement, owned, aggregate)) {
            project.addClass(clazz);
        }

        ClassSourcePanel sourcePanel = new ClassSourcePanel();
        sourcePanel.setProjectModel(project);
        sourcePanel.edit(source);
        StatementSourcePanel statementPanel = new StatementSourcePanel();
        statementPanel.setProjectModel(project);
        statementPanel.edit(statement);
        OwnedClassPanel ownedPanel = new OwnedClassPanel(project);
        ownedPanel.edit(owned);
        AggregateClassPanel aggregatePanel = new AggregateClassPanel(project);
        aggregatePanel.edit(aggregate);

        for (Container panel : List.of(sourcePanel, statementPanel, ownedPanel,
                aggregatePanel)) {
            List<JButton> buttons = new ArrayList<>();
            collect(panel, JButton.class, buttons);
            List<String> applying = buttons.stream()
                    .map(JButton::getText)
                    .filter(text -> text != null
                            && text.toLowerCase().startsWith("apply"))
                    .toList();
            assertEquals(List.of(), applying,
                    panel.getClass().getSimpleName()
                            + " offers a button for what every save already does");
        }
    }

    /**
     * And the edits still land: the workbench flushes the editor that owns the class
     * when the selection moves, which is what the buttons were standing in for.
     */
    @Test void anEditSurvivesLeavingTheClassWithoutPressingAnything() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel part = new GeneratedClassModel("Name");
        part.ownedClass(true);
        project.addClass(part);
        GeneratedClassModel other = new GeneratedClassModel("Person");
        project.addClass(other);

        ModelSourceWorkbenchPanel panel = new ModelSourceWorkbenchPanel(project);
        panel.edit(part);
        // The OWNED panel's header: all four kind editors are cards in this workbench,
        // so the first header found belongs to whichever card was built first.
        find(find(find(panel, OwnedClassPanel.class), ClassHeaderEditor.class),
                javax.swing.JTextField.class).setText("StructuredName");
        panel.changeSelection(other);

        assertEquals("StructuredName", part.className());
    }

    private static <T> T find(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found.isEmpty() ? null : found.get(0);
    }

    @SuppressWarnings("unchecked")
    private static <T> void collect(Container root, Class<?> type, List<T> into) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) into.add((T) child);
            if (child instanceof Container container) collect(container, type, into);
        }
    }
}
