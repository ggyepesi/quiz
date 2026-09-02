package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import workbench.WorkbenchSelections;

import javax.swing.*;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The edge property can be typed, not only handed over.
 *
 * <p>It used to arrive solely through Selections — "Use selected property as edge" —
 * so a PID the reader already knew took a detour through another panel to get here,
 * and until it arrived the panel showed "No property selected" and an inert button,
 * which reads as broken rather than unset.
 */
class EntityRelationPropertyFieldTest {

    private static <T extends Component> T find(Container root, Class<T> type, int index) {
        java.util.List<T> found = new java.util.ArrayList<>();
        collect(root, type, found);
        return found.size() > index ? found.get(index) : null;
    }

    private static <T extends Component> void collect(
            Container root, Class<T> type, java.util.List<T> found) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container container) collect(container, type, found);
        }
    }

    private static JLabel labelShowing(Container root, String... anyOf) {
        java.util.List<JLabel> labels = new java.util.ArrayList<>();
        collect(root, JLabel.class, labels);
        for (JLabel label : labels) {
            for (String text : anyOf) {
                if (text.equals(label.getText())) return label;
            }
        }
        return null;
    }

    @Test void aPidCanBeTypedDirectly() {
        EntityRelationDiscoveryPanel panel = new EntityRelationDiscoveryPanel();
        JTextField pid = find(panel, JTextField.class, 0);
        assertNotNull(pid, "there is a field for the edge property");
        assertNotNull(labelShowing(panel, "No property selected"),
                "and it starts empty, saying so");

        pid.setText("P279");

        assertNotNull(labelShowing(panel, "P279"),
                "a typed PID is shown as the edge property");
    }

    @Test void whatIsNotAPidIsSaidToBeSo() {
        EntityRelationDiscoveryPanel panel = new EntityRelationDiscoveryPanel();
        JTextField pid = find(panel, JTextField.class, 0);

        pid.setText("Q42");

        assertNotNull(labelShowing(panel, "Not a PID"),
                "a QID in the property box is a mistake worth naming");
    }

    /** Selections still works, and brings the property's name with it. */
    @Test void aHandedOverPropertyKeepsItsName() {
        EntityRelationDiscoveryPanel panel = new EntityRelationDiscoveryPanel();
        WorkbenchSelections selections = new WorkbenchSelections();
        selections.property("P279", "subclass of");
        panel.selections(selections);

        JTextField pid = find(panel, JTextField.class, 0);
        pid.setText("");   // start from nothing, as a reader would

        panel.useSelectedPropertyForTest(new WorkbenchSelections.Property("P279", "subclass of"));

        assertEquals("P279", pid.getText());
        assertNotNull(labelShowing(panel, "subclass of (P279)"),
                "the handed-over name describes the PID it came with");
    }

    /** Typing over a handed-over property drops its name rather than mislabelling. */
    @Test void typingOverAHandedOverPropertyDropsItsName() {
        EntityRelationDiscoveryPanel panel = new EntityRelationDiscoveryPanel();
        panel.useSelectedPropertyForTest(
                new WorkbenchSelections.Property("P279", "subclass of"));
        JTextField pid = find(panel, JTextField.class, 0);

        pid.setText("P1001");

        assertNull(labelShowing(panel, "subclass of (P1001)"),
                "the old name must not travel to a different property");
        assertNotNull(labelShowing(panel, "P1001"));
    }
}
