package workbench;

import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchSelectionsTest {
    @Test void entityAndPropertyAreIndependentSingleValueSlots() {
        WorkbenchSelections selections = new WorkbenchSelections();
        AtomicInteger changes = new AtomicInteger();
        selections.onChange(changes::incrementAndGet);

        selections.entity("Q42", "Douglas Adams");
        selections.property("P26", "spouse");
        selections.entity("Q937", "Albert Einstein");

        assertEquals("Q937", selections.entity().orElseThrow().qid());
        assertEquals("P26", selections.property().orElseThrow().pid());
        assertEquals(3, changes.get());

        selections.clearProperty();
        assertTrue(selections.property().isEmpty());
        assertEquals("Q937", selections.entity().orElseThrow().qid());
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
