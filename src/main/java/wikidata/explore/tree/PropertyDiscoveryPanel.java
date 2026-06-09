package wikidata.explore.tree;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.query.WikidataQueryBuilder;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Unified class-level property discovery panel (replaces NodePropertyDiscoveryPanel + WikidataItemAttributePanel).
 *
 * Always queries at class level by sampling N instances of a class, then profiling
 * which properties they have (outgoing) or which properties reference them (incoming).
 *
 * The class to sample comes from the current tree node (via nodeSupplier) by default.
 * The user may override with a custom class QID.
 */
public class PropertyDiscoveryPanel extends JPanel {

    private static final int DEFAULT_SAMPLE = 10;
    private static final int RESULT_LIMIT   = 100;

    private static final String TYPE_ITEM     = "http://wikiba.se/ontology#WikibaseItem";
    private static final String TYPE_STRING   = "http://wikiba.se/ontology#String";
    private static final String TYPE_MONO     = "http://wikiba.se/ontology#Monolingualtext";
    private static final String TYPE_QUANTITY = "http://wikiba.se/ontology#Quantity";
    private static final String TYPE_TIME     = "http://wikiba.se/ontology#Time";
    private static final String TYPE_URL      = "http://wikiba.se/ontology#Url";
    private static final String TYPE_COMMONS  = "http://wikiba.se/ontology#CommonsMedia";
    private static final String TYPE_GLOBE    = "http://wikiba.se/ontology#GlobeCoordinate";
    private static final String TYPE_EXT_ID   = "http://wikiba.se/ontology#ExternalId";
    private static final String TYPE_MATH     = "http://wikiba.se/ontology#Math";

    // --- External wiring ---
    private WikidataSparqlClient client;
    private Consumer<String> log = s -> {};
    private Supplier<RuleNode> nodeSupplier = () -> null;
    private Runnable applyEdits = () -> {};
    private Consumer<DiscoveredProperty> onAddField = p -> {};
    private Consumer<String> onAddAllowedQid  = qid -> {};
    private Consumer<String> onAddExcludedQid = qid -> {};

    // --- UI ---
    private final JLabel     nodeLabel       = new JLabel("No node selected");
    private final JTextField customQidField  = new JTextField(8);
    private final JLabel     customQidLabel  = new JLabel(" ");
    private final JSpinner   sampleSpinner   = new JSpinner(new SpinnerNumberModel(DEFAULT_SAMPLE, 1, 50, 5));
    private final JButton    runButton       = new JButton("Run");
    private final JButton    cancelButton    = new JButton("Cancel");
    private final JLabel     statusLabel     = new JLabel(" ");
    private final JTextField searchField     = new JTextField(12);

    // --- Table ---
    private final List<DiscoveredProperty>     properties = new ArrayList<>();
    private final PropTableModel               tableModel = new PropTableModel(properties);
    private final TableRowSorter<PropTableModel> sorter   = new TableRowSorter<>(tableModel);
    private final JTable                       table      = new JTable(tableModel);

    private SwingWorker<?, ?> currentWorker;
    private Timer qidResolveTimer;

    public PropertyDiscoveryPanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    // --- Wiring setters ---

    public void setClient(WikidataSparqlClient client) {
        this.client = client;
        if (client != null) {
            client.registerRunButton(runButton);
            client.registerCancelButton(cancelButton);
        }
    }

    public void log(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
    }

    public void setNodeSupplier(Supplier<RuleNode> supplier) {
        this.nodeSupplier = supplier == null ? () -> null : supplier;
    }

    public void setApplyEdits(Runnable applyEdits) {
        this.applyEdits = applyEdits == null ? () -> {} : applyEdits;
    }

    public void refreshNodeTitle() {
        RuleNode node = nodeSupplier.get();
        if (node == null) {
            nodeLabel.setText("No node selected");
            return;
        }
        String name = node.name() != null ? node.name() : "?";
        String qid  = node.sourceQid();
        nodeLabel.setText(name + (qid != null && !qid.isBlank() ? " (" + qid + ")" : ""));
    }

    public void onAddField(Consumer<DiscoveredProperty> handler) {
        this.onAddField = handler == null ? p -> {} : handler;
    }

    public void onAddAllowedQid(Consumer<String> handler) {
        this.onAddAllowedQid = handler == null ? qid -> {} : handler;
    }

    public void onAddExcludedQid(Consumer<String> handler) {
        this.onAddExcludedQid = handler == null ? qid -> {} : handler;
    }

    // --- UI build ---

    private void buildUi() {
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        table.setRowSorter(sorter);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        table.getColumnModel().getColumn(PropTableModel.COL_PID)       .setPreferredWidth(55);
        table.getColumnModel().getColumn(PropTableModel.COL_LABEL)     .setPreferredWidth(160);
        table.getColumnModel().getColumn(PropTableModel.COL_DIR)       .setPreferredWidth(65);
        table.getColumnModel().getColumn(PropTableModel.COL_TYPE)      .setPreferredWidth(75);
        table.getColumnModel().getColumn(PropTableModel.COL_COUNT)     .setPreferredWidth(65);
        table.getColumnModel().getColumn(PropTableModel.COL_EXAMPLE)   .setPreferredWidth(160);
        table.getColumnModel().getColumn(PropTableModel.COL_ADD_FIELD) .setPreferredWidth(80);
        table.getColumnModel().getColumn(PropTableModel.COL_ALLOW)     .setPreferredWidth(55);
        table.getColumnModel().getColumn(PropTableModel.COL_EXCLUDE)   .setPreferredWidth(60);

        ButtonRenderer btnRenderer = new ButtonRenderer();
        for (int c : new int[]{PropTableModel.COL_ADD_FIELD, PropTableModel.COL_ALLOW, PropTableModel.COL_EXCLUDE}) {
            table.getColumnModel().getColumn(c).setCellRenderer(btnRenderer);
        }
        table.getColumnModel().getColumn(PropTableModel.COL_ADD_FIELD)
                .setCellEditor(new ButtonEditor("Add Field", viewRow -> {
                    int r = table.convertRowIndexToModel(viewRow);
                    if (r >= 0 && r < properties.size()) onAddField.accept(properties.get(r));
                }));
        table.getColumnModel().getColumn(PropTableModel.COL_ALLOW)
                .setCellEditor(new ButtonEditor("Allow", viewRow -> {
                    int r = table.convertRowIndexToModel(viewRow);
                    if (r >= 0 && r < properties.size()) {
                        String qid = properties.get(r).exampleQid();
                        if (qid != null && !qid.isBlank()) onAddAllowedQid.accept(qid);
                    }
                }));
        table.getColumnModel().getColumn(PropTableModel.COL_EXCLUDE)
                .setCellEditor(new ButtonEditor("Exclude", viewRow -> {
                    int r = table.convertRowIndexToModel(viewRow);
                    if (r >= 0 && r < properties.size()) {
                        String qid = properties.get(r).exampleQid();
                        if (qid != null && !qid.isBlank()) onAddExcludedQid.accept(qid);
                    }
                }));

        WdLinkRenderer linkRenderer = new WdLinkRenderer();
        table.getColumnModel().getColumn(PropTableModel.COL_PID)    .setCellRenderer(linkRenderer);
        table.getColumnModel().getColumn(PropTableModel.COL_EXAMPLE).setCellRenderer(linkRenderer);

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                int vr = table.rowAtPoint(e.getPoint());
                int vc = table.columnAtPoint(e.getPoint());
                if (vr < 0) return;
                int mr = table.convertRowIndexToModel(vr);
                int mc = table.convertColumnIndexToModel(vc);
                if (mr < 0 || mr >= properties.size()) return;
                DiscoveredProperty p = properties.get(mr);
                if (mc == PropTableModel.COL_PID)
                    openInBrowser("https://www.wikidata.org/wiki/Property:" + p.pid());
                else if (mc == PropTableModel.COL_EXAMPLE && !p.exampleQid().isBlank())
                    openInBrowser("https://www.wikidata.org/wiki/" + p.exampleQid());
            }
        });
        table.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int vr = table.rowAtPoint(e.getPoint());
                int vc = table.columnAtPoint(e.getPoint());
                int mc = table.convertColumnIndexToModel(vc);
                int mr = vr >= 0 ? table.convertRowIndexToModel(vr) : -1;
                boolean link = false;
                if (mr >= 0 && mr < properties.size()) {
                    if (mc == PropTableModel.COL_PID) link = true;
                    if (mc == PropTableModel.COL_EXAMPLE) link = !properties.get(mr).exampleQid().isBlank();
                }
                table.setCursor(link ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        });

        nodeLabel.setFont(nodeLabel.getFont().deriveFont(Font.BOLD));
        customQidField.setToolTipText("Leave empty to use the current tree node; or enter a class QID to override");

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        controls.add(new JLabel("Class:"));
        controls.add(nodeLabel);
        controls.add(new JLabel("  Override QID:"));
        controls.add(customQidField);
        controls.add(customQidLabel);
        controls.add(new JLabel("N:"));
        controls.add(sampleSpinner);
        controls.add(runButton);
        controls.add(cancelButton);
        controls.add(new JLabel("  Search:"));
        controls.add(searchField);
        controls.add(statusLabel);

        add(controls, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        cancelButton.setEnabled(false);

        runButton.addActionListener(e -> runDiscovery());
        cancelButton.addActionListener(e -> cancel());

        customQidField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { scheduleQidResolve(); }
            public void removeUpdate(DocumentEvent e)  { scheduleQidResolve(); }
            public void changedUpdate(DocumentEvent e) {}
        });
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(DocumentEvent e) {}
        });
    }

    private void applyFilter() {
        String text = searchField.getText();
        sorter.setRowFilter(text == null || text.isBlank()
                ? null
                : RowFilter.regexFilter("(?i)" + Pattern.quote(text.trim())));
    }

    private void scheduleQidResolve() {
        customQidLabel.setText(" ");
        if (qidResolveTimer != null) qidResolveTimer.stop();
        String raw = customQidField.getText().trim();
        if (!raw.matches("Q\\d+")) return;
        qidResolveTimer = new Timer(600, e -> resolveQidLabel(raw));
        qidResolveTimer.setRepeats(false);
        qidResolveTimer.start();
    }

    private void resolveQidLabel(String qid) {
        if (client == null) return;
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                String sparql = "SELECT ?label WHERE { wd:" + qid
                        + " rdfs:label ?label . FILTER(LANG(?label)=\"en\") } LIMIT 1";
                for (WikidataBinding b : client.query(sparql)) return b.value("label");
                return null;
            }
            @Override protected void done() {
                try {
                    String label = get();
                    customQidLabel.setText(label != null ? qid + " · " + label : qid + " (no label)");
                } catch (Exception ignored) {}
            }
        }.execute();
    }

    // --- Discovery ---

    private void runDiscovery() {
        if (client == null) return;

        applyEdits.run();

        String customQid = customQidField.getText().trim();
        boolean useCustom = customQid.matches("Q\\d+");
        int     n         = (int) sampleSpinner.getValue();

        RuleNode node = nodeSupplier.get();
        if (node == null && !useCustom) {
            statusLabel.setText("Select a tree node or enter a class QID.");
            return;
        }

        if (!useCustom) {
            String name = node.name() != null ? node.name() : "?";
            String qid  = node.sourceQid();
            nodeLabel.setText(name + (qid != null && !qid.isBlank() ? " (" + qid + ")" : ""));
        }

        if (currentWorker != null && !currentWorker.isDone()) currentWorker.cancel(true);

        properties.clear();
        tableModel.fireTableDataChanged();
        statusLabel.setText("Fetching sample QIDs...");

        final RuleNode finalNode = node;
        final String   finalCustomQid = customQid;

        currentWorker = new SwingWorker<List<DiscoveredProperty>, String>() {
            @Override protected List<DiscoveredProperty> doInBackground() throws Exception {
                List<String> qids = useCustom
                        ? sampleByP31(finalCustomQid, n)
                        : sampleFromNode(finalNode, n);

                if (qids.isEmpty()) { publish("No sample instances found."); return List.of(); }

                publish("Got " + qids.size() + " items. Querying properties...");
                List<DiscoveredProperty> result = queryBothDirections(qids);
                publish("Found " + result.size() + " properties.");
                return result;
            }
            @Override protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) statusLabel.setText(chunks.getLast());
            }
            @Override protected void done() {
                try {
                    List<DiscoveredProperty> result = get();
                    properties.clear();
                    properties.addAll(result);
                    tableModel.fireTableDataChanged();
                    statusLabel.setText(result.isEmpty() ? "No properties found."
                            : result.size() + " properties found.");
                } catch (java.util.concurrent.CancellationException ignored) {
                    statusLabel.setText("Cancelled.");
                } catch (Exception ex) {
                    statusLabel.setText("Error: " + ex.getMessage());
                } finally { currentWorker = null; }
            }
        };
        currentWorker.execute();
    }

    private List<String> sampleFromNode(RuleNode node, int limit) throws Exception {
        RuleNode sample = NodeSamplePanel.sampleNode(node, limit);
        String sparql   = RuleTreeExtractor.valuesQuery(sample);
        log.accept("\nDiscover: sample QIDs for " + node.name() + "\n" + "-".repeat(40) + "\n" + sparql + "\n");
        List<String> qids = new ArrayList<>();
        for (WikidataBinding b : client.query(sparql)) {
            String qid = b.qid("value");
            if (qid != null && qid.matches("Q\\d+")) qids.add(qid);
        }
        return qids;
    }

    private List<String> sampleByP31(String classQid, int limit) throws Exception {
        String sparql = "SELECT ?item WHERE { ?item wdt:P31 wd:" + classQid + " . } LIMIT " + limit;
        log.accept("\nDiscover: sample instances of " + classQid + "\n" + "-".repeat(40) + "\n" + sparql + "\n");
        List<String> qids = new ArrayList<>();
        for (WikidataBinding b : client.query(sparql)) {
            String qid = b.qid("item");
            if (qid != null && qid.matches("Q\\d+")) qids.add(qid);
        }
        return qids;
    }

    private List<DiscoveredProperty> queryBothDirections(List<String> qids)
            throws Exception {

        String outgoingSparql = outgoingPropertiesQuery(qids);
        String incomingSparql =
                incomingPropertiesQuery(currentDiscoveryClassQid());

        log.accept("\nDiscover: outgoing properties ("
                           + qids.size()
                           + " items)\n"
                           + "-".repeat(40)
                           + "\n"
                           + outgoingSparql
                           + "\n");

        CompletableFuture<List<DiscoveredProperty>> outgoing =
                client.queryAsync(outgoingSparql)
                      .thenApply(rows ->
                                         parseBindings(
                                                 rows,
                                                 qids.size(),
                                                 "outgoing"));

        CompletableFuture<List<DiscoveredProperty>> incoming =
                client.queryAsync(incomingSparql)
                      .thenApply(rows ->
                                         parseBindings(
                                                 rows,
                                                 qids.size(),
                                                 "incoming"));

        return outgoing.thenCombine(incoming, (out, in) -> {
            List<DiscoveredProperty> merged = new ArrayList<>();
            merged.addAll(out);
            merged.addAll(in);
            return merged;
        }).get();
    }

    private String currentDiscoveryClassQid() {
        String customQid = customQidField.getText().trim();
        if (customQid.matches("Q\\d+")) {
            return customQid;
        }

        RuleNode node = nodeSupplier.get();
        return node == null ? "" : node.sourceQid();
    }

    private String outgoingPropertiesQuery(List<String> qids) {
        String values = WikidataQueryBuilder.wdList(qids);

        return """
            SELECT ?prop ?propLabel ?type ?count ?example
            WHERE {
              {
                SELECT ?propUri
                       (COUNT(DISTINCT ?item) AS ?count)
                       (SAMPLE(?example) AS ?example)
                WHERE {
                  VALUES ?item { %s }
                  ?item ?propUri ?example .
                  FILTER(STRSTARTS(
                    STR(?propUri),
                    "http://www.wikidata.org/prop/direct/"
                  ))
                }
                GROUP BY ?propUri
                ORDER BY DESC(?count)
                LIMIT %d
              }

              ?prop wikibase:directClaim ?propUri .
              OPTIONAL { ?prop wikibase:propertyType ?type . }

              SERVICE wikibase:label {
                bd:serviceParam wikibase:language "en" .
              }
            }
            ORDER BY DESC(?count)
            LIMIT %d
            """.formatted(values, RESULT_LIMIT, RESULT_LIMIT);
    }

    private String incomingPropertiesQuery(String classQid) {
        classQid = WikidataQueryBuilder.cleanQid(classQid);

        return """
            SELECT ?prop ?propLabel ?type ?count
            WHERE {
              {
                SELECT ?propUri
                       (COUNT(DISTINCT ?subject) AS ?count)
                WHERE {
                  ?subject ?propUri wd:%s .
                  FILTER(STRSTARTS(
                    STR(?subject),
                    "http://www.wikidata.org/entity/Q"
                  ))
                }
                GROUP BY ?propUri
                ORDER BY DESC(?count)
                LIMIT 40
              }

              ?prop wikibase:directClaim ?propUri .
              OPTIONAL { ?prop wikibase:propertyType ?type . }

              SERVICE wikibase:label {
                bd:serviceParam wikibase:language "en" .
              }
            }
            ORDER BY DESC(?count)
            LIMIT %d
            """.formatted(classQid, RESULT_LIMIT);
    }

    private List<DiscoveredProperty> parseBindings(
            List<WikidataBinding> bindings,
            int sampleSize,
            String direction) {

        List<DiscoveredProperty> result = new ArrayList<>();

        for (WikidataBinding b : bindings) {
            String pid = b.qid("prop");
            if (pid == null || !pid.matches("P\\d+")) {
                continue;
            }

            String label = b.label("prop");
            String typeUri = b.value("type");
            String countStr = b.value("count");
            String example = b.value("example");
            String exLabel = b.value("exampleLabel");

            int count = 0;
            try {
                count = Integer.parseInt(countStr);
            } catch (Exception ignored) {
            }

            String exQid = entityQid(example);
            String exDisplay =
                    exLabel != null && !exLabel.isBlank()
                            ? exLabel
                            : (!exQid.isBlank() ? exQid : localName(example));

            result.add(new DiscoveredProperty(
                    pid,
                    label != null && !label.isBlank() ? label : pid,
                    typeUri,
                    typeLabel(typeUri),
                    kindOf(typeUri),
                    count,
                    sampleSize,
                    exQid != null ? exQid : "",
                    exDisplay != null ? exDisplay : "",
                    direction));
        }

        return result;
    }

    private List<DiscoveredProperty> parseResults(String sparql, int sampleSize) throws Exception {
        List<DiscoveredProperty> result = new ArrayList<>();
        for (WikidataBinding b : client.query(sparql)) {
            String pid = b.qid("prop");
            if (pid == null || !pid.matches("P\\d+")) continue;
            String label       = b.label("prop");
            String typeUri     = b.value("type");
            String countStr    = b.value("count");
            String example     = b.value("example");
            String exLabel     = b.value("exampleLabel");
            String direction   = b.value("direction");
            if (direction == null) direction = "outgoing";
            int count = 0;
            try { count = Integer.parseInt(countStr); } catch (Exception ignored) {}
            String exQid     = entityQid(example);
            String exDisplay = exLabel != null && !exLabel.isBlank() ? exLabel
                    : (!exQid.isBlank() ? exQid : localName(example));
            result.add(new DiscoveredProperty(
                    pid,
                    label != null && !label.isBlank() ? label : pid,
                    typeUri,
                    typeLabel(typeUri),
                    kindOf(typeUri),
                    count,
                    sampleSize,
                    exQid != null ? exQid : "",
                    exDisplay != null ? exDisplay : "",
                    direction));
        }
        return result;
    }

    private void cancel() {
        if (currentWorker != null && !currentWorker.isDone()) {
            statusLabel.setText("Cancelling...");
            log.accept("Discovery cancelling...\n");
            currentWorker.cancel(true);
            client.cancelCurrentQuery();
        }
    }

    // --- Static helpers ---

    private static String entityQid(String value) {
        if (value == null) return "";
        int i = value.lastIndexOf('/');
        String tail = i >= 0 ? value.substring(i + 1) : value;
        return tail.matches("Q\\d+") ? tail : "";
    }

    private static String localName(String uri) {
        if (uri == null) return "";
        int i = uri.lastIndexOf('/');
        return i >= 0 ? uri.substring(i + 1) : uri;
    }

    private static String typeLabel(String uri) {
        if (uri == null) return "?";
        return switch (uri) {
            case TYPE_ITEM     -> "entity";
            case TYPE_STRING   -> "string";
            case TYPE_MONO     -> "text";
            case TYPE_QUANTITY -> "quantity";
            case TYPE_TIME     -> "date/time";
            case TYPE_URL      -> "URL";
            case TYPE_COMMONS  -> "media";
            case TYPE_GLOBE    -> "coordinate";
            case TYPE_EXT_ID   -> "external ID";
            case TYPE_MATH     -> "math";
            default            -> "other";
        };
    }

    private static PropertyKind kindOf(String uri) {
        if (uri == null) return PropertyKind.SCALAR;
        return switch (uri) {
            case TYPE_ITEM    -> PropertyKind.ENTITY;
            case TYPE_COMMONS -> PropertyKind.MEDIA;
            default           -> PropertyKind.SCALAR;
        };
    }

    private static void openInBrowser(String url) {
        try { Desktop.getDesktop().browse(new java.net.URI(url)); } catch (Exception ignored) {}
    }

    // --- Data model ---

    public enum PropertyKind { ENTITY, SCALAR, MEDIA }

    public record DiscoveredProperty(
            String pid,
            String label,
            String typeUri,
            String typeLabel,
            PropertyKind kind,
            int count,
            int sampleSize,
            String exampleQid,
            String exampleDisplay,
            String direction) {

        public String frequency() { return count + " / " + sampleSize; }

        public String fieldName() {
            String s = label == null || label.isBlank() ? pid : label;
            s = s.trim().replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("_+", "_");
            if (s.startsWith("_")) s = s.substring(1);
            if (s.endsWith("_"))   s = s.substring(0, s.length() - 1);
            if (s.isBlank())       s = "field";
            if (Character.isDigit(s.charAt(0))) s = "v_" + s;
            return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        }
    }

    // --- Table model ---

    static final class PropTableModel extends AbstractTableModel {
        static final int COL_PID       = 0;
        static final int COL_LABEL     = 1;
        static final int COL_DIR       = 2;
        static final int COL_TYPE      = 3;
        static final int COL_COUNT     = 4;
        static final int COL_EXAMPLE   = 5;
        static final int COL_ADD_FIELD = 6;
        static final int COL_ALLOW     = 7;
        static final int COL_EXCLUDE   = 8;

        private static final String[] COLUMNS =
                { "PID", "Label", "Dir", "Type", "N / sample", "Example", "Add Field", "Allow", "Exclude" };

        private final List<DiscoveredProperty> rows;

        PropTableModel(List<DiscoveredProperty> rows) { this.rows = rows; }

        @Override public int getRowCount()    { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }

        @Override public Class<?> getColumnClass(int col) {
            return (col == COL_ADD_FIELD || col == COL_ALLOW || col == COL_EXCLUDE)
                    ? JButton.class : String.class;
        }

        @Override public boolean isCellEditable(int r, int c) {
            return c == COL_ADD_FIELD || c == COL_ALLOW || c == COL_EXCLUDE;
        }

        @Override public Object getValueAt(int row, int col) {
            DiscoveredProperty p = rows.get(row);
            return switch (col) {
                case COL_PID       -> p.pid();
                case COL_LABEL     -> p.label();
                case COL_DIR       -> p.direction();
                case COL_TYPE      -> p.typeLabel();
                case COL_COUNT     -> p.frequency();
                case COL_EXAMPLE   -> p.exampleDisplay();
                case COL_ADD_FIELD -> "Add Field";
                case COL_ALLOW     -> p.exampleQid().isBlank() ? "" : "Allow";
                case COL_EXCLUDE   -> p.exampleQid().isBlank() ? "" : "Exclude";
                default -> "";
            };
        }
    }

    // --- Renderers / editors ---

    private static class WdLinkRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable table, Object value, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(table, value, sel, focus, row, col);
            String text = value == null ? "" : value.toString();
            if (!sel && text.matches("[PQ]\\d+"))
                setForeground(new Color(0, 80, 200));
            else if (!sel)
                setForeground(table.getForeground());
            return this;
        }
    }

    private static class ButtonRenderer extends JButton
            implements javax.swing.table.TableCellRenderer {
        ButtonRenderer() { setOpaque(true); }
        @Override public Component getTableCellRendererComponent(
                JTable table, Object value, boolean sel, boolean focus, int row, int col) {
            String text = value == null ? "" : value.toString();
            setText(text);
            setEnabled(!text.isBlank());
            return this;
        }
    }

    private static class ButtonEditor extends DefaultCellEditor {
        private final JButton button;
        private int currentRow;

        ButtonEditor(String label, java.util.function.IntConsumer action) {
            super(new JCheckBox());
            button = new JButton(label);
            button.addActionListener(e -> { fireEditingStopped(); if (button.isEnabled()) action.accept(currentRow); });
        }
        @Override public Component getTableCellEditorComponent(
                JTable table, Object value, boolean sel, int row, int col) {
            currentRow = row;
            String text = value == null ? "" : value.toString();
            button.setText(text);
            button.setEnabled(!text.isBlank());
            return button;
        }
        @Override public Object getCellEditorValue() { return button.getText(); }
    }
}
