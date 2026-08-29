package workbench;

import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("P26", selections.property().orElseThrow().pid());
        assertEquals(3, changes.get());

        selections.clearProperty();
        assertTrue(selections.property().isEmpty());
        assertEquals(2, selections.entities().size());
    }

    // Arity is the using side's question. A tool that walks from a starting point
    // cannot begin at six, and quietly beginning at whichever was picked last would be
    // an arbitrary answer — so the single accessor is empty until exactly one remains.
    @Test void theSingleAccessorIsEmptyUnlessExactlyOneIsSelected() {
        WorkbenchSelections selections = new WorkbenchSelections();
        assertTrue(selections.entity().isEmpty(), "none selected");

        selections.entity("Q80061", "Physiology or Medicine");
        assertEquals("Q80061", selections.entity().orElseThrow().qid());

        selections.entity("Q38104", "Physics");
        assertTrue(selections.entity().isEmpty(), "two selected is not one");
        assertEquals(2, selections.entities().size(), "but both remain available");

        selections.removeEntity(selections.entities().get(1));
        assertEquals("Q80061", selections.entity().orElseThrow().qid(),
                "removing the second leaves exactly one again");
    }

    @Test void selectingTheSameValueTwiceSelectsItOnce() {
        WorkbenchSelections selections = new WorkbenchSelections();
        selections.entity("Q38104", "Physics");
        selections.entity("Q38104", "Physics");
        assertEquals(1, selections.entities().size());
    }

    @Test void contextualMenuActionReadsCurrentStateWhenOpened() {
        WorkbenchSelections selections = new WorkbenchSelections();
        AtomicInteger invocations = new AtomicInteger();
        boolean[] enabled = {false};
        SelectionsButton button = new SelectionsButton(selections).action(
                "Set highlighted entity", () -> enabled[0], invocations::incrementAndGet);

        JMenuItem disabled = menuItem(button, "Set highlighted entity");
        assertTrue(!disabled.isEnabled());

        enabled[0] = true;
        JMenuItem active = menuItem(button, "Set highlighted entity");
        assertTrue(active.isEnabled());
        active.doClick();
        assertEquals(1, invocations.get());
    }

    private static JMenuItem menuItem(SelectionsButton button, String text) {
        for (var component : button.menu().getComponents()) {
            if (component instanceof JMenuItem item && text.equals(item.getText())) return item;
        }
        throw new AssertionError("Missing menu item: " + text);
    }
}
