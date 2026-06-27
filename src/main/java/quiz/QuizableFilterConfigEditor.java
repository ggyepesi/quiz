package quiz;

import quiz.ui.viewconfig.QuizablePanelConfig;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class QuizableFilterConfigEditor extends JPanel {
    private final QuizablePanelConfig fieldConfig;
    private final List<FieldOption> fieldOptions = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    private final RowTableModel tableModel = new RowTableModel();
    private final JTable table = new JTable(tableModel);

    private final JButton addButton = new JButton("Add filter");
    private final JButton clearButton = new JButton("Clear");

    public QuizableFilterConfigEditor(QuizablePanelConfig fieldConfig) {
        this(fieldConfig, null);
    }

    public QuizableFilterConfigEditor(
            QuizablePanelConfig fieldConfig,
            QuizableFilterConfig existing
    ) {
        this.fieldConfig = fieldConfig == null
                ? new QuizablePanelConfig()
                : fieldConfig.copy();

        setLayout(new BorderLayout(8, 8));

        buildFieldOptions();
        loadExisting(existing);
        configureTable();

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(addButton);
        buttons.add(clearButton);

        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        addButton.addActionListener(e -> addRow());
        clearButton.addActionListener(e -> clearRows());
    }

    private void buildFieldOptions() {
        fieldOptions.clear();

        List<QuizableFieldPaths.FieldPath> paths =
                QuizableFieldPaths.collect(
                        fieldConfig,
                        QuizableFieldPaths.NOT_IMAGE_PANE_FIELDS
                );

        for (QuizableFieldPaths.FieldPath fp : paths) {
            fieldOptions.add(new FieldOption(fp.title(), fp.path()));
        }
    }

    private void loadExisting(QuizableFilterConfig existing) {
        rows.clear();

        if (existing == null) {
            return;
        }

        for (QuizableFieldOperation op : existing.getOperations()) {
            Row row = new Row();

            row.field = findOrCreateOption(op.getPath());
            row.kind = op.getKind();
            row.argument = op.getArgument() == null ? "" : op.getArgument();

            rows.add(row);
        }
    }

    private FieldOption findOrCreateOption(List<String> path) {
        for (FieldOption option : fieldOptions) {
            if (samePath(option.path, path)) {
                return option;
            }
        }

        FieldOption custom =
                new FieldOption(
                        String.join(".", path),
                        path
                );

        fieldOptions.add(custom);
        return custom;
    }

    private boolean samePath(List<String> a, List<String> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }

        for (int i = 0; i < a.size(); i++) {
            if (!String.valueOf(a.get(i)).equals(String.valueOf(b.get(i)))) {
                return false;
            }
        }

        return true;
    }

    private void configureTable() {
        table.setRowHeight(28);
        table.setFillsViewportHeight(true);

        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setPreferredWidth(170);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);

        table.getColumnModel().getColumn(0).setCellEditor(new FieldEditor());
        table.getColumnModel().getColumn(1).setCellEditor(new KindEditor());

        table.getColumnModel().getColumn(3).setCellRenderer(new ButtonRenderer());
        table.getColumnModel().getColumn(3).setCellEditor(new RemoveButtonEditor());
    }

    private void addRow() {
        Row row = new Row();

        if (!fieldOptions.isEmpty()) {
            row.field = fieldOptions.get(0);
        }

        row.kind = QuizableFieldOperation.Kind.CONTAINS;
        row.argument = "";

        rows.add(row);

        int idx = rows.size() - 1;
        tableModel.fireTableRowsInserted(idx, idx);
    }

    private void clearRows() {
        rows.clear();
        tableModel.fireTableDataChanged();
    }

    public QuizableFilterConfig getConfig() {
        QuizableFilterConfig config = new QuizableFilterConfig();

        for (Row row : rows) {
            if (row.field == null || row.kind == null) {
                continue;
            }

            String argument = row.argument == null
                    ? ""
                    : row.argument.trim();

            if (requiresArgument(row.kind) && argument.isEmpty()) {
                continue;
            }

            config.addOperation(new QuizableFieldOperation(
                    row.field.path,
                    row.kind,
                    argument
            ));
        }

        return config;
    }

    private boolean requiresArgument(QuizableFieldOperation.Kind kind) {
        return switch (kind) {
            case EXISTS, EMPTY -> false;
            default -> true;
        };
    }

    private class RowTableModel extends AbstractTableModel {
        private final String[] columns = {
                "Field", "Operation", "Value", "Remove"
        };

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);

            return switch (columnIndex) {
                case 0 -> row.field;
                case 1 -> row.kind;
                case 2 -> row.argument;
                case 3 -> "Remove";
                default -> null;
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);

            switch (columnIndex) {
                case 0 -> row.field = (FieldOption) value;
                case 1 -> row.kind = (QuizableFieldOperation.Kind) value;
                case 2 -> row.argument = value == null ? "" : value.toString();
            }

            fireTableRowsUpdated(rowIndex, rowIndex);
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return String.class;
        }
    }

    private class FieldEditor extends DefaultCellEditor {
        FieldEditor() {
            super(new JComboBox<>(fieldOptions.toArray(new FieldOption[0])));
        }
    }

    private class KindEditor extends DefaultCellEditor {
        KindEditor() {
            super(new JComboBox<>(QuizableFieldOperation.Kind.values()));
        }
    }

    private class ButtonRenderer extends JButton implements TableCellRenderer {
        ButtonRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column
        ) {
            setText(value == null ? "" : value.toString());
            return this;
        }
    }

    private class RemoveButtonEditor extends AbstractCellEditor implements TableCellEditor {
        private final JButton button = new JButton("Remove");
        private int row = -1;

        RemoveButtonEditor() {
            button.addActionListener(e -> {
                fireEditingStopped();

                if (row >= 0 && row < rows.size()) {
                    rows.remove(row);
                    tableModel.fireTableDataChanged();
                }
            });
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table,
                Object value,
                boolean isSelected,
                int row,
                int column
        ) {
            this.row = table.convertRowIndexToModel(row);
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            return "Remove";
        }
    }

    private static class Row {
        FieldOption field;
        QuizableFieldOperation.Kind kind;
        String argument = "";
    }

    private static class FieldOption {
        final String title;
        final List<String> path;

        FieldOption(String title, List<String> path) {
            this.title = title;
            this.path = new ArrayList<>(path);
        }

        @Override
        public String toString() {
            return title;
        }
    }
}