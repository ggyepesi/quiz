package wikidata.explore.query.swing;

import wikidata.explore.query.core.QueryResultSink;
import wikidata.explore.query.result.TableQueryResult;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.regex.Pattern;

public class QueryTableResultPanel
        extends JPanel
        implements QueryResultSink<TableQueryResult> {

    private final JTextField searchField = new JTextField(18);
    private final JButton clearButton = new JButton("Clear");

    private final SimpleTableModel model = new SimpleTableModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<SimpleTableModel> sorter =
            new TableRowSorter<>(model);

    public QueryTableResultPanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    public JTable table() {
        return table;
    }

    @Override
    public void accept(TableQueryResult result) {
        SwingUtilities.invokeLater(() -> {
            clear();

            if (result == null) {
                return;
            }

            model.setColumns(result.columns());
            model.setRows(result.rows());
        });
    }

    public void clear() {
        model.setRows(java.util.List.of());
    }

    private void buildUi() {
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setRowSorter(sorter);

        table.setDefaultRenderer(Object.class, new LinkRenderer());

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());

                if (row < 0 || col < 0) {
                    return;
                }

                Object value = table.getValueAt(row, col);
                String text = value == null ? "" : value.toString();

                if (isLink(text)) {
                    openLink(text);
                }
            }
        });

        table.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());

                boolean link = false;
                if (row >= 0 && col >= 0) {
                    Object value = table.getValueAt(row, col);
                    link = isLink(value == null ? "" : value.toString());
                }

                table.setCursor(link
                                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                        : Cursor.getDefaultCursor());
            }
        });

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.add(new JLabel("Search:"));
        top.add(searchField);
        top.add(clearButton);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        searchField.getDocument().addDocumentListener(
                new javax.swing.event.DocumentListener() {
                    public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        applyFilter();
                    }

                    public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        applyFilter();
                    }

                    public void changedUpdate(javax.swing.event.DocumentEvent e) {}
                });

        clearButton.addActionListener(e -> clear());
    }

    private void applyFilter() {
        String text = searchField.getText();

        if (text == null || text.isBlank()) {
            sorter.setRowFilter(null);
            return;
        }

        sorter.setRowFilter(
                RowFilter.regexFilter("(?i)" + Pattern.quote(text.trim())));
    }

    private static boolean isLink(String text) {
        return text != null
                && (text.matches("Q\\d+")
                || text.matches("P\\d+")
                || text.startsWith("http://")
                || text.startsWith("https://"));
    }

    private static void openLink(String text) {
        String url;

        if (text.matches("Q\\d+")) {
            url = "https://www.wikidata.org/wiki/" + text;
        } else if (text.matches("P\\d+")) {
            url = "https://www.wikidata.org/wiki/Property:" + text;
        } else {
            url = text;
        }

        try {
            Desktop.getDesktop().browse(new java.net.URI(url));
        } catch (Exception ignored) {}
    }

    private static final class LinkRenderer
            extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean selected,
                boolean focus,
                int row,
                int col) {

            super.getTableCellRendererComponent(
                    table, value, selected, focus, row, col);

            String text = value == null ? "" : value.toString();

            if (!selected && isLink(text)) {
                setForeground(new Color(0, 80, 200));
                setText("<html><u>" + text + "</u></html>");
            } else {
                setText(text);
            }

            return this;
        }
    }

    private static final class SimpleTableModel extends AbstractTableModel {

        private java.util.List<String> columns = new java.util.ArrayList<>();
        private java.util.List<java.util.List<Object>> rows =
                new java.util.ArrayList<>();

        public void setColumns(java.util.List<String> columns) {
            this.columns =
                    columns == null
                            ? new java.util.ArrayList<>()
                            : new java.util.ArrayList<>(columns);
            fireTableStructureChanged();
        }

        public void setRows(java.util.List<java.util.List<Object>> rows) {
            this.rows =
                    rows == null
                            ? new java.util.ArrayList<>()
                            : new java.util.ArrayList<>(rows);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public String getColumnName(int column) {
            return columns.get(column);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            java.util.List<Object> row = rows.get(rowIndex);
            return columnIndex < row.size() ? row.get(columnIndex) : "";
        }
    }
}