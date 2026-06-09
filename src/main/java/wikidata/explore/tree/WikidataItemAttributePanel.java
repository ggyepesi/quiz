package wikidata.explore.tree;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Discovers the attributes of a specific Wikidata entity in both directions:
 *
 * Outgoing (QID → X): all properties this entity has (like P18 image, P47 borders, etc.)
 * Incoming (X → QID): all properties where other entities reference this entity
 *                      (e.g. stars whose P59 = Orion constellation)
 */
public class WikidataItemAttributePanel extends JPanel {

    private static final int INCOMING_LIMIT = 200;

    private static final String TYPE_ITEM     = "http://wikiba.se/ontology#WikibaseItem";
    private static final String TYPE_COMMONS  = "http://wikiba.se/ontology#CommonsMedia";
    private static final String TYPE_STRING   = "http://wikiba.se/ontology#String";
    private static final String TYPE_QUANTITY = "http://wikiba.se/ontology#Quantity";
    private static final String TYPE_TIME     = "http://wikiba.se/ontology#Time";
    private static final String TYPE_URL      = "http://wikiba.se/ontology#Url";
    private static final String TYPE_GLOBE    = "http://wikiba.se/ontology#GlobeCoordinate";
    private static final String TYPE_EXT_ID   = "http://wikiba.se/ontology#ExternalId";
    private static final String TYPE_MONO     = "http://wikiba.se/ontology#Monolingualtext";
    private static final String TYPE_MATH     = "http://wikiba.se/ontology#Math";

    private WikidataSparqlClient client;
    private SwingWorker<List<Row>, Void> currentWorker;

    private Consumer<String> log = s -> {};
    private BiConsumer<String, String> onUseProperty = (pid, label) -> {};

    private final JTextField qidField      = new JTextField(10);
    private final JButton outgoingButton   = new JButton("Outgoing (QID → X)");
    private final JButton incomingButton   = new JButton("Incoming (X → QID)");
    private final JButton cancelButton     = new JButton("Cancel");
    private final JLabel  statusLabel      = new JLabel(" ");
    private final JTextField searchField   = new JTextField(14);

    private final List<Row>        rows      = new ArrayList<>();
    private final RowTableModel    tableModel = new RowTableModel(rows);
    private final TableRowSorter<RowTableModel> sorter = new TableRowSorter<>(tableModel);
    private final JTable           table      = new JTable(tableModel);

    public WikidataItemAttributePanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    public void setClient(WikidataSparqlClient client) {
        this.client = client;
        if (client != null) {
            client.registerRunButton(incomingButton);
            client.registerRunButton(outgoingButton);
            client.registerCancelButton(cancelButton);
        }
    }

    /**
     * Called when the user clicks "Use property" on a discovered property row.
     * Both the PID (e.g. "P59") and the property label are passed.
     */
    public void log(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
    }

    public void onUseProperty(BiConsumer<String, String> handler) {
        this.onUseProperty = handler == null ? (p, l) -> {} : handler;
    }

    /** Pre-fills the QID field; does not trigger a query. */
    public void exploreQid(String qid) {
        if (qid != null) {
            qidField.setText(qid);
        }
    }

    private void buildUi() {
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        table.setRowSorter(sorter);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        table.getColumnModel().getColumn(RowTableModel.COL_PID).setPreferredWidth(55);
        table.getColumnModel().getColumn(RowTableModel.COL_LABEL).setPreferredWidth(165);
        table.getColumnModel().getColumn(RowTableModel.COL_TYPE).setPreferredWidth(75);
        table.getColumnModel().getColumn(RowTableModel.COL_COUNT).setPreferredWidth(65);
        table.getColumnModel().getColumn(RowTableModel.COL_EXAMPLE).setPreferredWidth(200);
        table.getColumnModel().getColumn(RowTableModel.COL_USE).setPreferredWidth(100);

        table.getColumnModel().getColumn(RowTableModel.COL_USE)
                .setCellRenderer(new ButtonRenderer());
        table.getColumnModel().getColumn(RowTableModel.COL_USE)
                .setCellEditor(new ButtonEditor("Use property", viewRow -> {
                    int modelRow = table.convertRowIndexToModel(viewRow);
                    if (modelRow >= 0 && modelRow < rows.size()) {
                        Row r = rows.get(modelRow);
                        onUseProperty.accept(r.pid(), r.label());
                    }
                }));

        table.getColumnModel().getColumn(RowTableModel.COL_PID)
                .setCellRenderer(new WdLinkRenderer());
        table.getColumnModel().getColumn(RowTableModel.COL_EXAMPLE)
                .setCellRenderer(new WdLinkRenderer());

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                int viewCol = table.columnAtPoint(e.getPoint());
                int viewRow = table.rowAtPoint(e.getPoint());
                if (viewRow < 0) return;
                int modelCol = table.convertColumnIndexToModel(viewCol);
                int modelRow = table.convertRowIndexToModel(viewRow);
                if (modelRow < 0 || modelRow >= rows.size()) return;
                Row r = rows.get(modelRow);
                if (modelCol == RowTableModel.COL_PID) {
                    openInBrowser("https://www.wikidata.org/wiki/Property:" + r.pid());
                } else if (modelCol == RowTableModel.COL_EXAMPLE) {
                    String ex = r.example();
                    if (ex != null && ex.matches("Q\\d+")) {
                        openInBrowser("https://www.wikidata.org/wiki/" + ex);
                    } else if (ex != null && ex.startsWith("http")) {
                        openInBrowser(ex);
                    }
                }
            }
        });
        table.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int viewCol = table.columnAtPoint(e.getPoint());
                int modelCol = table.convertColumnIndexToModel(viewCol);
                int viewRow = table.rowAtPoint(e.getPoint());
                boolean link = (modelCol == RowTableModel.COL_PID && viewRow >= 0)
                        || (modelCol == RowTableModel.COL_EXAMPLE && isLinkValue(viewRow));
                table.setCursor(link
                        ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                        : java.awt.Cursor.getDefaultCursor());
            }
        });

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        controls.add(new JLabel("QID:"));
        controls.add(qidField);
        controls.add(outgoingButton);
        controls.add(incomingButton);
        controls.add(cancelButton);
        controls.add(new JLabel("Search:"));
        controls.add(searchField);
        controls.add(statusLabel);

        JLabel hint = new JLabel(
                "Outgoing: properties this entity has.  "
                + "Incoming: other entities that reference this entity (e.g. stars with P59 = Orion).");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC));

        JPanel top = new JPanel(new BorderLayout(4, 2));
        top.add(controls, BorderLayout.CENTER);
        top.add(hint, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        outgoingButton.setEnabled(false);
        incomingButton.setEnabled(false);
        cancelButton.setEnabled(false);

        outgoingButton.addActionListener(e -> runQuery(false));
        incomingButton.addActionListener(e -> runQuery(true));

        cancelButton.addActionListener(e -> cancel());

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
    }

    private void applyFilter() {
        String text = searchField.getText();
        if (text == null || text.isBlank()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(text.trim())));
        }
    }

    private String cleanQid() {
        String raw = qidField.getText();
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.startsWith("wd:")) raw = raw.substring(3);
        return raw.matches("Q\\d+") ? raw : null;
    }

    private void runQuery(boolean incoming) {
        if (client == null) return;

        String qid = cleanQid();
        if (qid == null) {
            statusLabel.setText("Enter a valid QID (e.g. Q8928).");
            return;
        }

        if (currentWorker != null && !currentWorker.isDone()) {
            currentWorker.cancel(true);
        }

        rows.clear();
        tableModel.fireTableDataChanged();
        setBusy(true);
        statusLabel.setText((incoming ? "Incoming" : "Outgoing") + " query for " + qid + "...");

        String sparql = incoming ? buildIncomingQuery(qid) : buildOutgoingQuery(qid);
        log.accept("\nItem: " + (incoming ? "incoming" : "outgoing") + " for " + qid + "\n"
                + "-".repeat(40) + "\n" + sparql + "\n");

        currentWorker = new SwingWorker<>() {
            @Override
            protected List<Row> doInBackground() throws Exception {
                List<Row> result = new ArrayList<>();

                for (WikidataBinding b : client.query(sparql)) {
                    if (isCancelled()) break;

                    String pid = b.qid("prop");
                    log.accept("  row: pid=" + pid + " type=" + b.value("type") + " count=" + b.value("count") + "\n");
                    if (pid == null || !pid.matches("P\\d+")) continue;

                    String label    = b.label("prop");
                    String typeUri  = b.value("type");
                    String countStr = b.value("count");

                    String exDisplay;
                    if (incoming) {
                        String subjectLabel = b.value("subjectLabel");
                        String subjectQid   = b.qid("sampleSubject");
                        exDisplay = firstNonBlank(subjectLabel, subjectQid);
                    } else {
                        String exLabel = b.value("exampleLabel");
                        String exRaw   = b.value("example");
                        exDisplay = firstNonBlank(exLabel, localName(exRaw));
                    }

                    int count = 0;
                    try { count = Integer.parseInt(countStr); } catch (Exception ignored) {}

                    result.add(new Row(
                            pid,
                            label == null || label.isBlank() ? pid : label,
                            typeLabel(typeUri),
                            count,
                            exDisplay == null ? "" : exDisplay));
                }

                return result;
            }

            @Override
            protected void done() {
                setBusy(false);
                if (isCancelled()) {
                    statusLabel.setText("Cancelled.");
                    currentWorker = null;
                    return;
                }
                try {
                    List<Row> result = get();
                    rows.clear();
                    rows.addAll(result);
                    tableModel.fireTableDataChanged();
                    statusLabel.setText(result.size() + " properties found.");
                } catch (Exception ex) {
                    statusLabel.setText("Error: " + ex.getMessage());
                    ex.printStackTrace();
                } finally {
                    currentWorker = null;
                }
            }
        };

        currentWorker.execute();
    }

    private static String buildOutgoingQuery(String qid) {
        return "SELECT ?prop ?propLabel ?type\n"
                + "  (COUNT(?v) AS ?count)\n"
                + "  (SAMPLE(?v) AS ?example)\n"
                + "  (SAMPLE(?exampleLabel) AS ?exampleLabel)\n"
                + "WHERE {\n"
                + "  wd:" + qid + " ?propUri ?v .\n"
                + "  ?prop wikibase:directClaim ?propUri .\n"
                + "  OPTIONAL { ?prop wikibase:propertyType ?type . }\n"
                + "  OPTIONAL {\n"
                + "    ?v rdfs:label ?exampleLabel .\n"
                + "    FILTER(LANG(?exampleLabel) = \"en\")\n"
                + "  }\n"
                + "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"en\" . }\n"
                + "}\n"
                + "GROUP BY ?prop ?propLabel ?type\n"
                + "ORDER BY ?prop\n";
    }

    private static String buildIncomingQuery(String qid) {
        return "SELECT ?prop ?propLabel ?type\n"
                + "  (COUNT(DISTINCT ?subject) AS ?count)\n"
                + "  (SAMPLE(?subject) AS ?sampleSubject)\n"
                + "  (SAMPLE(?subjectLabel) AS ?subjectLabel)\n"
                + "WHERE {\n"
                + "  ?subject ?propUri wd:" + qid + " .\n"
                + "  FILTER(STRSTARTS(STR(?subject), STR(wd:)))\n"
                + "  ?prop wikibase:directClaim ?propUri .\n"
                + "  OPTIONAL { ?prop wikibase:propertyType ?type . }\n"
                + "  OPTIONAL {\n"
                + "    ?subject rdfs:label ?subjectLabel .\n"
                + "    FILTER(LANG(?subjectLabel) = \"en\")\n"
                + "  }\n"
                + "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"en\" . }\n"
                + "}\n"
                + "GROUP BY ?prop ?propLabel ?type\n"
                + "ORDER BY DESC(?count)\n"
                + "LIMIT " + INCOMING_LIMIT + "\n";
    }

    private static String typeLabel(String uri) {
        if (uri == null) return "?";
        return switch (uri) {
            case TYPE_ITEM     -> "entity";
            case TYPE_COMMONS  -> "media";
            case TYPE_STRING   -> "string";
            case TYPE_MONO     -> "text";
            case TYPE_QUANTITY -> "quantity";
            case TYPE_TIME     -> "date/time";
            case TYPE_URL      -> "URL";
            case TYPE_GLOBE    -> "coordinate";
            case TYPE_EXT_ID   -> "external ID";
            case TYPE_MATH     -> "math";
            default            -> "other";
        };
    }

    private static String localName(String uri) {
        if (uri == null) return null;
        int idx = uri.lastIndexOf('/');
        return idx >= 0 ? uri.substring(idx + 1) : uri;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private void setBusy(boolean busy) {
        setCursor(Cursor.getPredefinedCursor(busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private void cancel() {
        if (currentWorker != null && !currentWorker.isDone()) {
            currentWorker.cancel(true);
            client.cancelCurrentQuery();
        }
    }

    private boolean isLinkValue(int viewRow) {
        if (viewRow < 0) return false;
        int modelRow = table.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= rows.size()) return false;
        String ex = rows.get(modelRow).example();
        return ex != null && (ex.matches("Q\\d+") || ex.startsWith("http"));
    }

    private static void openInBrowser(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
        } catch (Exception ignored) {}
    }

    public record Row(String pid, String label, String typeLabel, int count, String example) {}

    private static class WdLinkRenderer extends javax.swing.table.DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            String text = value == null ? "" : value.toString();
            boolean link = text.matches("[PQ]\\d+") || text.startsWith("http");
            if (link && !isSelected) setForeground(new java.awt.Color(0, 80, 200));
            else if (!isSelected) setForeground(table.getForeground());
            return this;
        }
    }

    static final class RowTableModel extends AbstractTableModel {

        static final int COL_PID     = 0;
        static final int COL_LABEL   = 1;
        static final int COL_TYPE    = 2;
        static final int COL_COUNT   = 3;
        static final int COL_EXAMPLE = 4;
        static final int COL_USE     = 5;

        private static final String[] COLUMNS =
                { "PID", "Label", "Type", "Count", "Example / Sample", "Use" };

        private final List<Row> rows;

        RowTableModel(List<Row> rows) {
            this.rows = rows;
        }

        @Override public int     getRowCount()               { return rows.size(); }
        @Override public int     getColumnCount()            { return COLUMNS.length; }
        @Override public String  getColumnName(int col)      { return COLUMNS[col]; }
        @Override public boolean isCellEditable(int r, int c){ return c == COL_USE; }

        @Override
        public Class<?> getColumnClass(int col) {
            return col == COL_USE ? JButton.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int col) {
            Row r = rows.get(row);
            return switch (col) {
                case COL_PID     -> r.pid();
                case COL_LABEL   -> r.label();
                case COL_TYPE    -> r.typeLabel();
                case COL_COUNT   -> String.valueOf(r.count());
                case COL_EXAMPLE -> r.example();
                case COL_USE     -> "Use property";
                default          -> "";
            };
        }
    }

    private static class ButtonRenderer
            extends JButton
            implements javax.swing.table.TableCellRenderer {

        ButtonRenderer() { setOpaque(true); }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            setText(value == null ? "" : value.toString());
            return this;
        }
    }

    private static class ButtonEditor extends DefaultCellEditor {

        private final JButton button;
        private int currentRow;

        ButtonEditor(String label, java.util.function.IntConsumer action) {
            super(new JCheckBox());
            button = new JButton(label);
            button.addActionListener(e -> {
                fireEditingStopped();
                action.accept(currentRow);
            });
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table, Object value, boolean isSelected, int row, int column) {
            currentRow = row;
            button.setText(value == null ? "" : value.toString());
            return button;
        }

        @Override
        public Object getCellEditorValue() { return button.getText(); }
    }
}
