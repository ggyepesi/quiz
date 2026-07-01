package wikidata.explore.workbench;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reusable Wikidata result table: a filter/search box, rows of values, one
 * column rendered as a clickable QID link (opens the entity on Wikidata), and
 * single/multi row selection. Factored out so the WikiProject, Explore (and
 * future) panels share the same search + links + selection behaviour.
 */
public class EntityResultPanel extends JPanel {

    private final int qidColumn;
    private final DefaultTableModel model;
    private final JTable table;
    private final TableRowSorter<DefaultTableModel> sorter;
    private final JTextField filterField = new JTextField(18);

    private Runnable onSelectionChanged = () -> {};

    public EntityResultPanel(List<String> columns, int qidColumn, boolean multiSelect) {
        super(new BorderLayout(4, 4));
        this.qidColumn = qidColumn;

        model = new DefaultTableModel(columns.toArray(), 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(model);
        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        table.setSelectionMode(multiSelect
                ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                : ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setFillsViewportHeight(true);

        if (qidColumn >= 0 && qidColumn < columns.size()) {
            WikidataLinks.installOnColumn(table, qidColumn);
        }

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onSelectionChanged.run();
            }
        });

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.add(new JLabel("Filter:"));
        top.add(filterField);
        filterField.setToolTipText("Type to filter the rows below (any column).");
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    private void applyFilter() {
        String text = filterField.getText();
        if (text == null || text.isBlank()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.regexFilter(
                    "(?i)" + Pattern.quote(text.trim())));
        }
    }

    /** Sets the column preferred widths (by column index). */
    public void setColumnWidths(int... widths) {
        for (int i = 0; i < widths.length && i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
    }

    public void onSelectionChanged(Runnable r) {
        this.onSelectionChanged = r == null ? () -> {} : r;
    }

    public void setRows(List<List<Object>> rows) {
        model.setRowCount(0);
        if (rows != null) {
            for (List<Object> row : rows) {
                model.addRow(row.toArray());
            }
        }
    }

    public boolean hasSelection() {
        return table.getSelectedRow() >= 0;
    }

    /** Selected rows as their underlying model values (filter/sort-aware). */
    public List<List<Object>> selectedRows() {
        List<List<Object>> out = new ArrayList<>();
        for (int viewRow : table.getSelectedRows()) {
            int r = table.convertRowIndexToModel(viewRow);
            List<Object> row = new ArrayList<>();
            for (int c = 0; c < model.getColumnCount(); c++) {
                row.add(model.getValueAt(r, c));
            }
            out.add(row);
        }
        return out;
    }

    /** The QID-column values of selected rows that look like QIDs. */
    public List<String> selectedQids() {
        List<String> out = new ArrayList<>();
        if (qidColumn < 0) {
            return out;
        }
        for (List<Object> row : selectedRows()) {
            Object v = qidColumn < row.size() ? row.get(qidColumn) : null;
            String s = v == null ? "" : v.toString().trim();
            if (s.matches("Q\\d+") && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /** A single named column's value for the first selected row (or ""). */
    public String firstSelected(int column) {
        List<List<Object>> sel = selectedRows();
        if (sel.isEmpty() || column < 0 || column >= sel.get(0).size()) {
            return "";
        }
        Object v = sel.get(0).get(column);
        return v == null ? "" : v.toString();
    }

}
