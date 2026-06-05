package quiz;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public class QuizablePanelConfigEditor extends JPanel {

    private final QuizablePanelConfig sourceConfig;
    private final List<Row> rows = new ArrayList<>();

    private final RowTableModel tableModel = new RowTableModel();
    private final JTable table = new JTable(tableModel);
    private final boolean nestedDefaultNameOnly;
    private final boolean minorOnly;

    private final JCheckBox allMinorFieldsBox = new JCheckBox("All minor fields");

    public QuizablePanelConfigEditor(QuizablePanelConfig config) {
        this(config, false, false);
    }

    public QuizablePanelConfigEditor(QuizablePanelConfig config,
                                     boolean nestedDefaultNameOnly) {
        this(config, nestedDefaultNameOnly, false);
    }

    private QuizablePanelConfigEditor(QuizablePanelConfig config,
                                      boolean nestedDefaultNameOnly,
                                      boolean minorOnly) {
        this.sourceConfig = config == null
                ? new QuizablePanelConfig()
                : config.copy();

        this.nestedDefaultNameOnly = nestedDefaultNameOnly;
        this.minorOnly = minorOnly;

        setLayout(new BorderLayout(8, 8));

        if (!minorOnly) {
            allMinorFieldsBox.setSelected(sourceConfig.isAllMinorFields());
            allMinorFieldsBox.addActionListener(e -> tableModel.fireTableDataChanged());

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
            top.add(allMinorFieldsBox);
            add(top, BorderLayout.NORTH);
        }

        buildRows();

        table.setRowHeight(28);
        table.setFillsViewportHeight(true);

        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(60);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(60);
        table.getColumnModel().getColumn(5).setPreferredWidth(100);

        for (int col : new int[]{3, 4, 5}) {
            table.getColumnModel().getColumn(col).setCellRenderer(new ButtonRenderer());
            table.getColumnModel().getColumn(col).setCellEditor(new ButtonEditor());
        }

        table.setDefaultRenderer(Object.class,
                new RowRenderer(table.getDefaultRenderer(Object.class)));
        table.setDefaultRenderer(Boolean.class,
                new RowRenderer(table.getDefaultRenderer(Boolean.class)));

        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    private void buildRows() {
        rows.clear();

        Class<? extends Quizable> cls = sourceConfig.getCls();
        if (cls == null) {
            return;
        }

        if (minorOnly) {
            addFieldRows(cls, true);
        } else {
            addFieldRows(cls, false);

            if (hasMinorFields(cls)) {
                rows.add(Row.minorBlock());
            }
        }
    }

    private boolean hasMinorFields(Class<? extends Quizable> cls) {
        for (Field field : QuizableAdapter.getAllFields(cls)) {
            if (!Modifier.isStatic(field.getModifiers())
                    && QuizableAdapter.isMinorField(field)) {
                return true;
            }
        }
        return false;
    }

    private void addFieldRows(Class<? extends Quizable> cls, boolean minor) {
        for (Field field : QuizableAdapter.getAllFields(cls)) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            if (QuizableAdapter.isMinorField(field) != minor) {
                continue;
            }

            String fieldName = field.getName();
            QuizablePanelConfig selectedChild = sourceConfig.getFieldConfig(fieldName);
            Class<? extends Quizable> nestedClass =
                    QuizableFieldPaths.nestedQuizableClass(field);

            Row row = Row.field(field, nestedClass);
            row.use = sourceConfig.showsField(field);

            if (selectedChild != null
                    && nestedClass != null
                    && !selectedChild.getFields().isEmpty()) {
                row.childEditor = new QuizablePanelConfigEditor(
                        selectedChild,
                        nestedDefaultNameOnly);
            }

            rows.add(row);
        }
    }

    public QuizablePanelConfig getConfig() {
        QuizablePanelConfig out = copyHeader(sourceConfig);

        out.setAllFields(false);
        out.setAllMinorFields(!minorOnly && allMinorFieldsBox.isSelected());
        out.getFields().clear();

        for (Row row : rows) {
            if (row.special || !row.use) {
                continue;
            }

            // If "all minor fields" is checked, no need to explicitly store every minor field.
            if (!minorOnly
                    && out.isAllMinorFields()
                    && QuizableAdapter.isMinorField(row.field)
                    && sourceConfig.getFieldConfig(row.field.getName()) == null) {
                continue;
            }

            out.addField(row.field.getName(), childConfigFor(row));
        }

        if (!minorOnly) {
            Row minorBlock = findMinorBlock();
            if (minorBlock != null && minorBlock.childEditor != null) {
                QuizablePanelConfig minorConfig = minorBlock.childEditor.getConfig();
                out.setAllMinorFields(minorConfig.isAllMinorFields() || out.isAllMinorFields());

                for (var e : minorConfig.getFields().entrySet()) {
                    out.addField(e.getKey(), e.getValue());
                }
            }
        }

        return out;
    }

    private Row findMinorBlock() {
        for (Row row : rows) {
            if (row.minorBlock) {
                return row;
            }
        }
        return null;
    }

    private QuizablePanelConfig childConfigFor(Row row) {
        if (row.childEditor != null) {
            return row.childEditor.getConfig();
        }

        if (row.nestedClass != null) {
            return QuizablePanelConfig.of(row.nestedClass);
        }

        return QuizablePanelConfig.leaf();
    }

    private QuizablePanelConfig copyHeader(QuizablePanelConfig src) {
        QuizablePanelConfig out = new QuizablePanelConfig();

        if (src == null) {
            return out;
        }

        out.setCls(src.getCls());
        out.setAllFields(src.isAllFields());
        out.setAllMinorFields(src.isAllMinorFields());
        out.setAddListener(src.isAddListener());
        out.setThumb(src.isThumb());
        out.setAnswerType(src.getAnswerType());

        return out;
    }

    private void moveRow(Row row, int delta) {
        if (row.special) {
            return;
        }

        int from = rows.indexOf(row);
        if (from < 0) {
            return;
        }

        int to = from + delta;
        if (to < 0 || to >= rows.size() || rows.get(to).special) {
            return;
        }

        rows.remove(from);
        rows.add(to, row);

        tableModel.fireTableDataChanged();

        int viewRow = table.convertRowIndexToView(to);
        if (viewRow >= 0) {
            table.getSelectionModel().setSelectionInterval(viewRow, viewRow);
        }
    }

    private void openMinorEditor(Row row) {
        if (!row.minorBlock) {
            return;
        }

        if (row.childEditor == null) {
            QuizablePanelConfig cfg = sourceConfig.copy();
            cfg.setAllMinorFields(allMinorFieldsBox.isSelected());
            row.childEditor = new QuizablePanelConfigEditor(cfg, nestedDefaultNameOnly, true);
        }

        JDialog dialog = new JDialog(
                SwingUtilities.getWindowAncestor(this),
                "Minor fields : " + sourceConfig.getCls().getSimpleName(),
                Dialog.ModalityType.APPLICATION_MODAL);

        dialog.setLayout(new BorderLayout(8, 8));
        dialog.add(row.childEditor, BorderLayout.CENTER);

        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            QuizablePanelConfig cfg = row.childEditor.getConfig();
            allMinorFieldsBox.setSelected(cfg.isAllMinorFields());
            dialog.dispose();
            tableModel.fireTableDataChanged();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(okButton);

        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(800, 600);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void openChildEditor(Row row) {
        if (row.minorBlock) {
            openMinorEditor(row);
            return;
        }

        if (row.special || row.nestedClass == null) {
            return;
        }

        row.use = true;

        if (row.childEditor == null) {
            QuizablePanelConfig childConfig =
                    sourceConfig.getFieldConfig(row.field.getName());

            if (childConfig == null || childConfig.getCls() == null) {
                childConfig = nestedDefaultNameOnly
                        ? nameOnlyConfig(row.nestedClass)
                        : QuizablePanelConfig.all(row.nestedClass);
            } else {
                childConfig = childConfig.copy();
            }

            row.childEditor = new QuizablePanelConfigEditor(
                    childConfig,
                    nestedDefaultNameOnly);
        }

        JDialog dialog = new JDialog(
                SwingUtilities.getWindowAncestor(this),
                row.field.getName() + " : " + row.nestedClass.getSimpleName(),
                Dialog.ModalityType.APPLICATION_MODAL);

        dialog.setLayout(new BorderLayout(8, 8));
        dialog.add(row.childEditor, BorderLayout.CENTER);

        JButton clearButton = new JButton("Clear custom config");
        JButton okButton = new JButton("OK");

        clearButton.addActionListener(e -> {
            row.childEditor = null;
            dialog.dispose();
            tableModel.fireTableDataChanged();
        });

        okButton.addActionListener(e -> {
            dialog.dispose();
            tableModel.fireTableDataChanged();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(clearButton);
        buttons.add(okButton);

        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(800, 600);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);

        tableModel.fireTableDataChanged();
    }

    private QuizablePanelConfig nameOnlyConfig(Class<? extends Quizable> cls) {
        QuizablePanelConfig cfg = QuizablePanelConfig.of(cls);
        cfg.setAllFields(false);
        cfg.setAllMinorFields(false);
        cfg.setAddListener(false);
        cfg.setThumb(false);
        cfg.addField("name", QuizablePanelConfig.leaf());
        return cfg;
    }

    private String describeFieldType(Field field) {
        Class<?> type = field.getType();

        if (Quizable.class.isAssignableFrom(type)) {
            return type.getSimpleName();
        }

        if (java.util.Collection.class.isAssignableFrom(type)) {
            Class<? extends Quizable> nested =
                    QuizableFieldPaths.nestedQuizableClass(field);

            return nested != null
                    ? "Collection<" + nested.getSimpleName() + ">"
                    : "Collection";
        }

        if (java.util.Map.class.isAssignableFrom(type)) {
            Class<? extends Quizable> nested =
                    QuizableFieldPaths.nestedQuizableClass(field);

            return nested != null
                    ? "Map<?, " + nested.getSimpleName() + ">"
                    : "Map";
        }

        return type.getSimpleName();
    }

    private int countSelectedMinorFields() {
        if (sourceConfig.isAllMinorFields()) {
            return -1;
        }

        int count = 0;
        Class<? extends Quizable> cls = sourceConfig.getCls();

        if (cls == null) {
            return 0;
        }

        for (Field f : QuizableAdapter.getAllFields(cls)) {
            if (QuizableAdapter.isMinorField(f)
                    && sourceConfig.getFieldConfig(f.getName()) != null) {
                count++;
            }
        }

        Row minorBlock = findMinorBlock();
        if (minorBlock != null && minorBlock.childEditor != null) {
            QuizablePanelConfig cfg = minorBlock.childEditor.getConfig();
            count = 0;
            for (Field f : QuizableAdapter.getAllFields(cls)) {
                if (QuizableAdapter.isMinorField(f)
                        && cfg.getFieldConfig(f.getName()) != null) {
                    count++;
                }
            }
        }

        return count;
    }

    private class RowTableModel extends AbstractTableModel {
        private final String[] columns = {
                "Field", "Type", "Use", "Up", "Down", "Expand"
        };

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);

            if (row.minorBlock) {
                int n = countSelectedMinorFields();
                return switch (columnIndex) {
                    case 0 -> "Minor fields";
                    case 1 -> allMinorFieldsBox.isSelected()
                            ? "all"
                            : n + " selected";
                    case 2 -> allMinorFieldsBox.isSelected();
                    case 5 -> "Open...";
                    default -> "";
                };
            }

            return switch (columnIndex) {
                case 0 -> row.field.getName();
                case 1 -> describeFieldType(row.field);
                case 2 -> row.use;
                case 3 -> "↑";
                case 4 -> "↓";
                case 5 -> row.nestedClass == null
                        ? ""
                        : row.childEditor == null ? "Expand" : "Edit...";
                default -> null;
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);

            if (row.minorBlock) {
                return columnIndex == 2 || columnIndex == 5;
            }

            return columnIndex == 2
                    || columnIndex == 3
                    || columnIndex == 4
                    || (columnIndex == 5 && row.nestedClass != null);
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);

            if (row.minorBlock && columnIndex == 2) {
                allMinorFieldsBox.setSelected(Boolean.TRUE.equals(value));
                fireTableRowsUpdated(rowIndex, rowIndex);
                return;
            }

            if (columnIndex == 2) {
                row.use = Boolean.TRUE.equals(value);

                if (!row.use) {
                    row.childEditor = null;
                }

                fireTableRowsUpdated(rowIndex, rowIndex);
            }
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 2 ? Boolean.class : String.class;
        }
    }

    private class RowRenderer implements TableCellRenderer {
        private final TableCellRenderer delegate;

        RowRenderer(TableCellRenderer delegate) {
            this.delegate = delegate;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table,
                                                       Object value,
                                                       boolean isSelected,
                                                       boolean hasFocus,
                                                       int row,
                                                       int column) {
            int modelRow = table.convertRowIndexToModel(row);
            Row r = rows.get(modelRow);

            if (r.special && column != 2) {
                JLabel label = new JLabel(value == null ? "" : value.toString());
                label.setOpaque(true);
                label.setFont(label.getFont().deriveFont(Font.BOLD));
                label.setBackground(new Color(235, 235, 235));
                label.setForeground(Color.DARK_GRAY);
                return label;
            }

            return delegate.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
        }
    }

    private class ButtonRenderer extends JButton implements TableCellRenderer {
        ButtonRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table,
                                                       Object value,
                                                       boolean isSelected,
                                                       boolean hasFocus,
                                                       int row,
                                                       int column) {
            int modelRow = table.convertRowIndexToModel(row);
            Row r = rows.get(modelRow);

            setText(value == null ? "" : value.toString());
            setEnabled(value != null
                    && !value.toString().isEmpty()
                    && (!r.special || r.minorBlock));

            return this;
        }
    }

    private class ButtonEditor extends AbstractCellEditor implements TableCellEditor {
        private final JButton button = new JButton();
        private Row currentRow;
        private int currentColumn;
        private boolean opening = false;

        ButtonEditor() {
            button.addActionListener(e -> {
                if (opening) return;

                Row row = currentRow;
                int col = currentColumn;

                fireEditingStopped();

                if (row == null) return;

                if (row.minorBlock && col == 5) {
                    opening = true;
                    SwingUtilities.invokeLater(() -> {
                        try {
                            openMinorEditor(row);
                        } finally {
                            opening = false;
                        }
                    });
                    return;
                }

                if (row.special) return;

                if (col == 3) {
                    moveRow(row, -1);
                } else if (col == 4) {
                    moveRow(row, 1);
                } else if (col == 5) {
                    opening = true;
                    SwingUtilities.invokeLater(() -> {
                        try {
                            openChildEditor(row);
                        } finally {
                            opening = false;
                        }
                    });
                }
            });
        }

        @Override
        public Component getTableCellEditorComponent(JTable table,
                                                     Object value,
                                                     boolean isSelected,
                                                     int viewRow,
                                                     int viewColumn) {
            int modelRow = table.convertRowIndexToModel(viewRow);

            currentRow = rows.get(modelRow);
            currentColumn = table.convertColumnIndexToModel(viewColumn);

            button.setText(value == null ? "" : value.toString());
            button.setEnabled(value != null && !value.toString().isEmpty());

            return button;
        }

        @Override
        public Object getCellEditorValue() {
            return "";
        }
    }

    private static class Row {
        final boolean special;
        final boolean minorBlock;
        final Field field;
        final Class<? extends Quizable> nestedClass;

        boolean use;
        QuizablePanelConfigEditor childEditor;

        private Row(boolean special,
                    boolean minorBlock,
                    Field field,
                    Class<? extends Quizable> nestedClass) {
            this.special = special;
            this.minorBlock = minorBlock;
            this.field = field;
            this.nestedClass = nestedClass;
        }

        static Row minorBlock() {
            return new Row(true, true, null, null);
        }

        static Row field(Field field, Class<? extends Quizable> nestedClass) {
            return new Row(false, false, field, nestedClass);
        }
    }
}