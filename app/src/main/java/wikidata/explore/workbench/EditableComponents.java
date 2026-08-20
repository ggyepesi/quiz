package wikidata.explore.workbench;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.text.JTextComponent;

import java.awt.Component;
import java.awt.Container;

/**
 * Turns a component tree read-only without turning it off.
 *
 * <p>Locking a window by disabling the window itself stops input of every kind — a
 * disabled frame does not even close on some platforms — and disabling a whole panel
 * takes reading away along with writing. A lock owes the reader the opposite: everything
 * stays legible, scrollable and selectable, and only the surfaces that CHANGE something
 * stop responding.
 *
 * <p>So text keeps its caret and its copy, a list keeps its selection, and a table stays
 * navigable unless its own cells can be typed into — selecting a row is inspection,
 * editing it is not. Buttons, combo boxes and spinners are the surfaces that act, and
 * they are what goes quiet.
 */
public final class EditableComponents {

    /** Rows sampled to decide whether a table can be typed into. A configuration table
     *  declares the same editability for every row, so sampling keeps a long one cheap. */
    private static final int EDITABLE_SAMPLE_ROWS = 50;

    private EditableComponents() {}

    public static void setEditable(Component component, boolean editable) {
        if (component == null) return;

        if (component instanceof JTextComponent text) {
            text.setEditable(editable);
        } else if (component instanceof JTable table) {
            if (editableCells(table)) {
                table.setEnabled(editable);
            } else if (!editable && table.isEditing()) {
                table.getCellEditor().cancelCellEditing();
            }
        } else if (component instanceof JList<?>) {
            // Selecting a row changes nothing; whatever acts on a selection is a button.
        } else if (component instanceof AbstractButton
                || component instanceof JComboBox<?>
                || component instanceof JSpinner) {
            component.setEnabled(editable);
        }

        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                setEditable(child, editable);
            }
        }
    }

    private static boolean editableCells(JTable table) {
        int rows = Math.min(table.getRowCount(), EDITABLE_SAMPLE_ROWS);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < table.getColumnCount(); column++) {
                if (table.isCellEditable(row, column)) return true;
            }
        }
        return false;
    }
}
