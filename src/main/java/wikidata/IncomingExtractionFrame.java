package wikidata;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class IncomingExtractionFrame extends JFrame {

    private final WorkbenchController controller;

    private final JTextField rootQidField = new JTextField("Q10570", 12);
    private final JTextField rootLabelField = new JTextField("", 18);
    private final JTextField excludeTypeQidsField = new JTextField(28);
    private final JTextField searchField = new JTextField(28);

    private final JSpinner valueLimitSpinner =
            new JSpinner(new SpinnerNumberModel(200, 1, 5000, 50));

    private final JCheckBox requireLabelBox =
            new JCheckBox("Require English label", true);

    private final JCheckBox includeMediaBox =
            new JCheckBox("Include image P18", false);

    private final JButton extractButton =
            new JButton("Extract selected values");

    private final PropertyTableModel propertyTableModel =
            new PropertyTableModel();

    private final JTable propertyTable =
            new JTable(propertyTableModel);

    private final TableRowSorter<PropertyTableModel> sorter =
            new TableRowSorter<>(propertyTableModel);

    public IncomingExtractionFrame(WorkbenchController controller) {
        super("Incoming Extraction Planner");
        this.controller = controller;

        buildUi();
        wireActions();
        loadLocalProperties();

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        pack();
        setLocationByPlatform(true);
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));

        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridy = 0;

        c.gridx = 0;
        top.add(new JLabel("Root QID:"), c);

        c.gridx = 1;
        top.add(rootQidField, c);

        c.gridx = 2;
        top.add(new JLabel("Root label:"), c);

        c.gridx = 3;
        top.add(rootLabelField, c);

        c.gridx = 4;
        top.add(new JLabel("Value limit:"), c);

        c.gridx = 5;
        top.add(valueLimitSpinner, c);

        c.gridx = 6;
        top.add(requireLabelBox, c);

        c.gridx = 7;
        top.add(includeMediaBox, c);

        c.gridx = 8;
        top.add(extractButton, c);

        c.gridy = 1;

        c.gridx = 0;
        top.add(new JLabel("Exclude type QIDs:"), c);

        c.gridx = 1;
        c.gridwidth = 8;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        top.add(excludeTypeQidsField, c);

        c.gridy = 2;

        c.gridx = 0;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0.0;
        top.add(new JLabel("Search property:"), c);

        c.gridx = 1;
        c.gridwidth = 8;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        top.add(searchField, c);

        add(top, BorderLayout.NORTH);

        propertyTable.setAutoCreateRowSorter(false);
        propertyTable.setRowSorter(sorter);
        propertyTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        propertyTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        propertyTable.setRowHeight(36);

        propertyTable.getColumnModel().getColumn(0).setMaxWidth(70);
        propertyTable.getColumnModel().getColumn(1).setMaxWidth(90);
        propertyTable.getColumnModel().getColumn(2).setPreferredWidth(220);
        propertyTable.getColumnModel().getColumn(3).setPreferredWidth(650);
        propertyTable.getColumnModel().getColumn(3).setCellRenderer(
                new TextAreaRenderer());

        JScrollPane tableScroll = new JScrollPane(propertyTable);
        tableScroll.setPreferredSize(new Dimension(1100, 520));

        add(tableScroll, BorderLayout.CENTER);
    }

    private void wireActions() {
        extractButton.addActionListener(e -> extractSelectedValues());

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateSearchFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateSearchFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateSearchFilter();
            }
        });
    }

    private void loadLocalProperties() {
        setBusy(true);

        controller.loadPropertyCache(rows -> SwingUtilities.invokeLater(() -> {
            propertyTableModel.setRows(rows);
            setBusy(false);
        }));
    }

    private void extractSelectedValues() {
        List<IncomingPropertyRow> selected =
                propertyTableModel.getSelectedRows();

        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Check at least one property in the Use column.",
                    "No property selected",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        controller.loadIncomingValuesForProperties(
                rootQid(),
                selected,
                valueLimit(),
                requireLabelBox.isSelected(),
                includeMediaBox.isSelected(),
                excludeTypeQidsText());
    }

    private String excludeTypeQidsText() {
        return excludeTypeQidsField.getText().trim();
    }

    private void updateSearchFilter() {
        String text = searchField.getText().trim();

        if (text.isBlank()) {
            sorter.setRowFilter(null);
            return;
        }

        sorter.setRowFilter(RowFilter.regexFilter(
                "(?i)" + Pattern.quote(text),
                1, 2, 3));
    }

    private String rootQid() {
        String qid = rootQidField.getText().trim();

        if (qid.startsWith("wd:")) {
            qid = qid.substring(3);
        }

        return qid;
    }

    private String rootLabel() {
        String label = rootLabelField.getText().trim();
        return label.isBlank() ? rootQid() : label;
    }

    private List<String> excludeTypeQids() {
        String text = excludeTypeQidsField.getText().trim();
        List<String> qids = new ArrayList<>();

        if (text.isBlank()) {
            return qids;
        }

        for (String s : text.split("[,\\s]+")) {
            s = s.trim();

            if (s.startsWith("wd:")) {
                s = s.substring(3);
            }

            if (!s.isBlank()) {
                qids.add(s);
            }
        }

        return qids;
    }

    private int valueLimit() {
        return ((Number) valueLimitSpinner.getValue()).intValue();
    }

    private void setBusy(boolean busy) {
        extractButton.setEnabled(!busy);
        extractButton.setText(
                busy
                        ? "Loading properties..."
                        : "Extract selected values");
    }

    public static class IncomingPropertyRow {
        private boolean selected;
        private final String pid;
        private final String propertyQid;
        private final String label;
        private final String description;
        private final long count;

        public IncomingPropertyRow(
                boolean selected,
                String pid,
                String propertyQid,
                String label,
                long count) {

            this(selected, pid, propertyQid, label, "", count);
        }

        public IncomingPropertyRow(
                boolean selected,
                String pid,
                String propertyQid,
                String label,
                String description,
                long count) {

            this.selected = selected;
            this.pid = pid;
            this.propertyQid = propertyQid;
            this.label = label == null ? "" : label;
            this.description = description == null ? "" : description;
            this.count = count;
        }

        public boolean selected() {
            return selected;
        }

        public void selected(boolean selected) {
            this.selected = selected;
        }

        public String pid() {
            return pid;
        }

        public String propertyQid() {
            return propertyQid;
        }

        public String label() {
            return label;
        }

        public String description() {
            return description;
        }

        public long count() {
            return count;
        }
    }

    private static class PropertyTableModel extends AbstractTableModel {

        private final String[] columns = {
                "Use", "PID", "Label", "Description"
        };

        private final List<IncomingPropertyRow> rows =
                new ArrayList<>();

        public void setRows(List<IncomingPropertyRow> newRows) {
            rows.clear();
            rows.addAll(newRows);
            fireTableDataChanged();
        }

        public List<IncomingPropertyRow> getSelectedRows() {
            List<IncomingPropertyRow> selected = new ArrayList<>();

            for (IncomingPropertyRow row : rows) {
                if (row.selected()) {
                    selected.add(row);
                }
            }

            return selected;
        }

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
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0
                    ? Boolean.class
                    : String.class;
        }

        @Override
        public boolean isCellEditable(
                int rowIndex,
                int columnIndex) {

            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(
                int rowIndex,
                int columnIndex) {

            IncomingPropertyRow row = rows.get(rowIndex);

            return switch (columnIndex) {
                case 0 -> row.selected();
                case 1 -> row.pid();
                case 2 -> row.label();
                case 3 -> row.description();
                default -> null;
            };
        }

        @Override
        public void setValueAt(
                Object value,
                int rowIndex,
                int columnIndex) {

            if (columnIndex == 0) {
                rows.get(rowIndex).selected(Boolean.TRUE.equals(value));
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }
    }

    private static class TextAreaRenderer extends JTextArea
            implements TableCellRenderer {

        TextAreaRenderer() {
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column) {

            setText(value == null ? "" : value.toString());

            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            } else {
                setBackground(table.getBackground());
                setForeground(table.getForeground());
            }

            setFont(table.getFont());

            int width = table.getColumnModel()
                             .getColumn(column)
                             .getWidth();

            if (width > 0) {
                setSize(width, Short.MAX_VALUE);
            }

            int preferredHeight =
                    Math.max(36, getPreferredSize().height);

            if (table.getRowHeight(row) != preferredHeight) {
                table.setRowHeight(row, preferredHeight);
            }

            return this;
        }
    }
}