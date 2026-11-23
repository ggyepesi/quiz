package domain.workbench.modelbuilder.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredConstructListTest {

    @Test void disablingAddKeepsExistingChildrenInspectableAndRemovable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConfiguredConstructList<String, String> list =
                    new ConfiguredConstructList<>("Recipes");
            list.kinds(List.of("Infobox"));
            list.factory(kind -> kind);
            list.add("Infobox");

            list.allowAdding(false);

            assertFalse(named(list, "configured.construct.kind", JComboBox.class)
                    .isEnabled());
            assertFalse(named(list, "configured.construct.add", JButton.class)
                    .isEnabled());
            assertTrue(named(list, "configured.construct.remove", JButton.class)
                    .isEnabled(), "an incompatible existing child must remain removable");
        });
    }

    /** The recipes are an authored ordered list, so their order must be editable without
     *  removing and re-adding a child; moving keeps the moved child selected. */
    @Test void configuredChildrenCanBeReorderedInPlace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConfiguredConstructList<String, String> list =
                    new ConfiguredConstructList<>("Recipes");
            list.kinds(List.of("Table rows", "Infobox"));
            list.factory(kind -> kind);
            list.add("Table rows");
            list.add("Infobox");
            JButton up = named(list, "configured.construct.up", JButton.class);
            JButton down = named(list, "configured.construct.down", JButton.class);
            assertTrue(up.isEnabled());
            assertFalse(down.isEnabled(), "the last child cannot move further down");

            up.doClick();

            assertEquals(List.of("Infobox", "Table rows"), list.values());
            assertEquals("Infobox", list.selected());
            assertFalse(up.isEnabled(), "the first child cannot move further up");
        });
    }

    private static <T extends Component> T named(
            Container root, String name, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (name.equals(component.getName())) return type.cast(component);
            if (component instanceof Container child) {
                T found = find(child, name, type);
                if (found != null) return found;
            }
        }
        throw new AssertionError("No component named " + name);
    }

    private static <T extends Component> T find(
            Container root, String name, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (name.equals(component.getName())) return type.cast(component);
            if (component instanceof Container child) {
                T found = find(child, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
