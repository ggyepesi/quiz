package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import workbench.EntityResultPanel;

import javax.swing.JTable;
import java.awt.Component;
import java.awt.Container;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeSamplePanelTest {

    @Test void classInstancesUseTheCanonicalTwoColumnEntityPresentation() {
        NodeSamplePanel sample = new NodeSamplePanel();
        EntityResultPanel entities = component(sample, EntityResultPanel.class);
        JTable table = component(entities, JTable.class);

        assertEquals(2, table.getColumnCount());
        assertEquals("QID", table.getColumnName(0));
        assertEquals("Label", table.getColumnName(1));
    }

    private static <T extends Component> T component(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) {
                try { return component(container, type); }
                catch (AssertionError ignored) { }
            }
        }
        throw new AssertionError("No " + type.getSimpleName());
    }
}
