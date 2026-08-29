package workbench;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchSelectionsTest {
    @Test void entitiesAndPropertiesAccumulateIndependently() {
        WorkbenchSelections selections = new WorkbenchSelections();
        AtomicInteger changes = new AtomicInteger();
        selections.onChange(changes::incrementAndGet);

        selections.entity("Q42", "Douglas Adams");
        selections.property("P26", "spouse");
        selections.entity("Q937", "Albert Einstein");

        assertEquals(List.of("Q42", "Q937"), selections.entities().stream()
                .map(WorkbenchSelections.Entity::qid).toList());
        assertEquals("P26", selections.properties().getFirst().pid());
        assertEquals(3, changes.get());

        selections.clearProperty();
        assertTrue(selections.properties().isEmpty());
        assertEquals(2, selections.entities().size());
    }

    @Test void usingSideDecidesWhetherTheDialogSelectionIsSingleOrMultiple() {
        WorkbenchSelections selections = new WorkbenchSelections();
        selections.entity("Q80061", "Physiology or Medicine");
        selections.entity("Q38104", "Physics");
        SelectionsButton single = new SelectionsButton(selections).useEntities(
                "Use selected entity", SelectionsButton.Cardinality.SINGLE, ignored -> { });
        SelectionsButton multiple = new SelectionsButton(selections).useEntities(
                "Use selected entities", SelectionsButton.Cardinality.MULTIPLE, ignored -> { });

        assertEquals(ListSelectionModel.SINGLE_SELECTION,
                entityList(single).getSelectionMode());
        assertEquals(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION,
                entityList(multiple).getSelectionMode());
    }

    @Test void selectingTheSameValueTwiceSelectsItOnce() {
        WorkbenchSelections selections = new WorkbenchSelections();
        selections.entity("Q38104", "Physics");
        selections.entity("Q38104", "Nobel Prize in Physics");
        assertEquals(1, selections.entities().size());
        assertEquals("Nobel Prize in Physics", selections.entities().getFirst().label(),
                "a better label updates the same source identity");
    }

    @Test void aListenerRegistrationCanBeDetached() {
        WorkbenchSelections selections = new WorkbenchSelections();
        AtomicInteger changes = new AtomicInteger();
        WorkbenchSelections.Registration registration =
                selections.onChange(changes::incrementAndGet);

        selections.entity("Q38104", "Physics");
        registration.close();
        selections.entity("Q44585", "Chemistry");

        assertEquals(1, changes.get());
    }

    @Test void contextualAddActionReadsCurrentStateWhenDialogIsRealized() {
        WorkbenchSelections selections = new WorkbenchSelections();
        AtomicInteger invocations = new AtomicInteger();
        boolean[] enabled = {false};
        SelectionsButton button = new SelectionsButton(selections).addEntities(
                "Add selected entities", () -> enabled[0], invocations::incrementAndGet);

        JButton disabled = component(button.dialogContent(), JButton.class, "Add selected entities");
        assertTrue(!disabled.isEnabled());

        enabled[0] = true;
        JButton active = component(button.dialogContent(), JButton.class, "Add selected entities");
        assertTrue(active.isEnabled());
        active.doClick();
        assertEquals(1, invocations.get());
    }

    @Test void dialogHasTypedTabsAndRemovalRequiresAnExplicitButton() {
        WorkbenchSelections selections = new WorkbenchSelections();
        selections.entity("Q42", "Douglas Adams");
        SelectionsButton button = new SelectionsButton(selections).addEntities(
                "Add selected entities", () -> false, () -> { });
        JTabbedPane tabs = (JTabbedPane) button.dialogContent();
        assertEquals(List.of("Entities", "Properties"), List.of(
                tabs.getTitleAt(0), tabs.getTitleAt(1)));

        @SuppressWarnings("unchecked") JList<WorkbenchSelections.Entity> list =
                (JList<WorkbenchSelections.Entity>) component(
                        (Container) tabs.getComponentAt(0), JList.class, null);
        list.setSelectedIndex(0);
        assertEquals(1, selections.entities().size());
        component((Container) tabs.getComponentAt(0), JButton.class,
                "Remove selected entities").doClick();
        assertTrue(selections.entities().isEmpty());
    }

    private static <T extends Component> T component(
            Container root, Class<T> type, String text) {
        for (Component candidate : root.getComponents()) {
            if (type.isInstance(candidate)
                    && (text == null || candidate instanceof AbstractButton button
                    && text.equals(button.getText()))) return type.cast(candidate);
            if (candidate instanceof Container child) {
                T found = componentOrNull(child, type, text);
                if (found != null) return found;
            }
        }
        throw new AssertionError("Missing component: " + type.getSimpleName() + " " + text);
    }
    private static <T extends Component> T componentOrNull(
            Container root, Class<T> type, String text) {
        try { return component(root, type, text); }
        catch (AssertionError ignored) { return null; }
    }

    private static JList<?> entityList(SelectionsButton button) {
        JTabbedPane tabs = (JTabbedPane) button.dialogContent();
        return component((Container) tabs.getComponentAt(0), JList.class, null);
    }

    /**
     * Removal used to appear only where an ADD action was registered, so a tool that
     * merely USES the collection could show a wrong value without offering any way to
     * drop it. The dialog is the collection's editor in every context.
     */
    @Test void aUseOnlyDialogCanStillCorrectTheCollection() {
        WorkbenchSelections selections = new WorkbenchSelections();
        selections.entity("Q42", "Douglas Adams");
        selections.property("P31", "instance of");
        SelectionsButton button = new SelectionsButton(selections).useProperties(
                "Use selected property", SelectionsButton.Cardinality.SINGLE, values -> { });

        JTabbedPane tabs = (JTabbedPane) button.dialogContent();
        Container entityTab = (Container) tabs.getComponentAt(0);
        component(entityTab, JList.class, null).setSelectedIndex(0);
        component(entityTab, JButton.class, "Remove selected entities").doClick();

        assertTrue(selections.entities().isEmpty(), "an entity can be dropped here too");
        assertEquals(1, selections.properties().size());
    }

    @Test void twoUseActionsThatDisagreeAboutArityAreRefusedWhereTheyAreWired() {
        SelectionsButton button = new SelectionsButton(new WorkbenchSelections())
                .useEntities("Use one", SelectionsButton.Cardinality.SINGLE, values -> { });

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> button.useEntities("Use several",
                        SelectionsButton.Cardinality.MULTIPLE, values -> { }));
        assertTrue(refused.getMessage().contains("Use several"),
                "the message names the action that disagrees");

        assertDoesNotThrow(() -> button.useProperties("Use several properties",
                SelectionsButton.Cardinality.MULTIPLE, values -> { }),
                "the other tab has its own list and its own arity");
    }
}
