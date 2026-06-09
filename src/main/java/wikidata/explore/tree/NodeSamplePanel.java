package wikidata.explore.tree;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.model.FieldCardinality;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Samples either:
 * - selected class instances
 * - selected field values for sampled parent class instances
 */
public class NodeSamplePanel extends JPanel {

    public static final int SAMPLE_LIMIT = 8;

    private WikidataSparqlClient client;
    private Runnable applyEdits = () -> {
    };

    private Supplier<RuleNode> nodeSupplier =
            () -> null;

    private Supplier<FieldSampleContext> fieldSampleSupplier =
            () -> null;

    private Consumer<String> log =
            s -> {
            };

    private Consumer<FieldCardinality> onCardinalitySuggested = c -> {};

    private SwingWorker<List<Row>, Void> currentWorker;

    private final JButton classSampleButton =
            new JButton("Sample class instances");

    private final JButton fieldSampleButton =
            new JButton("Sample selected field");

    private final JButton cancelButton =
            new JButton("Cancel sample");

    private final JLabel statusLabel =
            new JLabel(" ");

    private final JLabel contextLabel =
            new JLabel(" ");

    private final JLabel cardinalityHintLabel =
            new JLabel("");

    private final DefaultTableModel tableModel =
            new DefaultTableModel(
                    new Object[]{"Parent QID", "Parent label", "Value QID/value", "Value label"}, 0) {
                @Override
                public boolean isCellEditable(int r, int c) {
                    return false;
                }
            };

    private final JTable table =
            new JTable(tableModel);

    private final JTextArea sparqlArea =
            new JTextArea();

    public NodeSamplePanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    public void setClient(WikidataSparqlClient client) {
        this.client = client;
        if (client != null) {
            client.registerRunButton(classSampleButton);
            client.registerRunButton(fieldSampleButton);
            client.registerCancelButton(cancelButton);
        }
    }

    public void setApplyEdits(Runnable applyEdits) {
        this.applyEdits =
                applyEdits == null ? () -> {
                } : applyEdits;
    }

    public void setNodeSupplier(Supplier<RuleNode> supplier) {
        this.nodeSupplier =
                supplier == null ? () -> null : supplier;
    }

    public void setFieldSampleSupplier(
            Supplier<FieldSampleContext> supplier) {

        this.fieldSampleSupplier =
                supplier == null ? () -> null : supplier;
    }

    public void log(Consumer<String> log) {
        this.log =
                log == null ? s -> {
                } : log;
    }

    public void onCardinalitySuggested(Consumer<FieldCardinality> c) {
        this.onCardinalitySuggested = c == null ? card -> {} : c;
    }

    private void buildUi() {
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        installLinkBehavior();

        sparqlArea.setEditable(false);
        sparqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        JTabbedPane tabs =
                new JTabbedPane();

        tabs.addTab("Results", new JScrollPane(table));
        tabs.addTab("SPARQL", new JScrollPane(sparqlArea));

        contextLabel.setFont(contextLabel.getFont().deriveFont(Font.BOLD));

        JPanel top =
                new JPanel(new BorderLayout(4, 2));

        JPanel buttonRow =
                new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));

        buttonRow.add(classSampleButton);
        buttonRow.add(fieldSampleButton);
        buttonRow.add(cancelButton);
        buttonRow.add(statusLabel);
        buttonRow.add(cardinalityHintLabel);

        top.add(contextLabel, BorderLayout.NORTH);
        top.add(buttonRow, BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);

        classSampleButton.setEnabled(false);
        fieldSampleButton.setEnabled(false);
        cancelButton.setEnabled(false);
        cardinalityHintLabel.setVisible(false);

        classSampleButton.addActionListener(e -> runClassSample());
        fieldSampleButton.addActionListener(e -> runFieldSample());
        cancelButton.addActionListener(e -> cancelSample());
    }

    public void triggerFieldSample() {
        runFieldSample();
    }

    private void runClassSample() {
        if (client == null) {
            return;
        }

        applyEdits.run();

        RuleNode node =
                nodeSupplier.get();

        if (node == null) {
            statusLabel.setText("Select the class to sample instances.");
            log.accept("SAMPLE class skipped: no class selected.\n");
            return;
        }

        String nodeName   = node.name() != null ? node.name() : "?";
        String sourceQid  = node.sourceQid() != null && !node.sourceQid().isBlank()
                ? " (" + node.sourceQid() + ")" : "";
        contextLabel.setText("Class instances:  " + nodeName + sourceQid);

        tableModel.setColumnIdentifiers(
                new Object[]{"QID", nodeName, "", ""});

        RuleNode sample =
                sampleNode(node, SAMPLE_LIMIT);

        String sparql =
                RuleTreeQueries.valuesQueryWithoutIncludedFields(sample);

        sparqlArea.setText(sparql);
        tableModel.setRowCount(0);
        statusLabel.setText("Running class sample...");

        log.accept("\nSAMPLE class instances\n----------------------\n");
        log.accept(sparql + "\n");

        setBusy(true);

        currentWorker =
                new SwingWorker<>() {
                    @Override
                    protected List<Row> doInBackground() throws Exception {
                        List<Row> out =
                                new ArrayList<>();

                        for (WikidataBinding b : client.query(sparql)) {
                            if (isCancelled()) {
                                break;
                            }

                            String qid =
                                    b.qid("value");

                            String label =
                                    b.label("value");

                            if (qid != null && qid.matches("Q\\d+")) {
                                out.add(new Row(qid, label, "", ""));
                            }
                        }

                        return out;
                    }

                    @Override
                    protected void done() {
                        finishSample("class");
                    }
                };

        currentWorker.execute();
    }

    private void runFieldSample() {
        if (client == null) {
            return;
        }

        applyEdits.run();

        FieldSampleContext context =
                fieldSampleSupplier.get();

        if (context == null || context.field() == null) {
            statusLabel.setText("Select a field to sample field values.");
            log.accept("SAMPLE field skipped: no field selected.\n");
            return;
        }

        RuleNode parentSample =
                sampleNode(
                        RuleTreeCompiler.compileClass(context.ownerClass()),
                        SAMPLE_LIMIT);

        RuleIncludedField includedField =
                RuleTreeCompiler.compileField(context.field());

        if (includedField == null) {
            statusLabel.setText("Selected field has no Wikidata property.");
            log.accept("SAMPLE field skipped: selected field has no property.\n");
            return;
        }

        String ownerName  = context.ownerClass().className();
        String fieldPid   = includedField.propertyPid() != null ? includedField.propertyPid() : "?";
        String propLabel  = includedField.propertyLabel() != null && !includedField.propertyLabel().isBlank()
                ? includedField.propertyLabel()
                : (context.field().name() != null ? context.field().name() : fieldPid);
        contextLabel.setText(
                ownerName
                + "  —  " + fieldPid
                + " · " + propLabel
                + "  →  value");

        tableModel.setColumnIdentifiers(new Object[]{
                ownerName + " QID",
                ownerName,
                fieldPid + " · " + propLabel,
                propLabel + " label"
        });

        tableModel.setRowCount(0);
        cardinalityHintLabel.setVisible(false);
        statusLabel.setText("Sampling field values...");
        setBusy(true);

        log.accept("\nSAMPLE selected field: "
                + context.field().name()
                + "\n----------------------\n");

        currentWorker =
                new SwingWorker<>() {
                    @Override
                    protected List<Row> doInBackground() throws Exception {
                        List<Parent> parents =
                                loadParentSample(parentSample);

                        if (isCancelled() || parents.isEmpty()) {
                            return List.of();
                        }

                        List<String> parentQids =
                                parents.stream()
                                       .map(Parent::qid)
                                       .toList();

                        String sparql =
                                RuleTreeQueries.fieldValueSampleQuery(
                                        includedField,
                                        parentQids,
                                        parentSample.labelConfig(),
                                        SAMPLE_LIMIT * 3);

                        SwingUtilities.invokeLater(() ->
                                sparqlArea.setText(sparql));

                        log.accept(sparql + "\n");

                        Map<String, Parent> parentByQid =
                                new java.util.LinkedHashMap<>();

                        for (Parent p : parents) {
                            parentByQid.put(p.qid(), p);
                        }

                        String var =
                                RuleIncludedFieldSparql.variableName(
                                        includedField,
                                        0);

                        List<Row> rows =
                                new ArrayList<>();

                        for (WikidataBinding b : client.query(sparql)) {
                            if (isCancelled()) {
                                break;
                            }

                            String parentQid =
                                    b.qid("parent");

                            Parent parent =
                                    parentByQid.get(parentQid);

                            if (parent == null) {
                                continue;
                            }

                            String value =
                                    includedField.isMediaField()
                                            ? b.value(var)
                                            : firstNonBlank(
                                                    b.qid(var),
                                                    b.value(var));

                            String label =
                                    b.value(var + "Label");

                            rows.add(new Row(
                                    parent.qid(),
                                    parent.label(),
                                    value,
                                    label));
                        }

                        return rows;
                    }

                    @Override
                    protected void done() {
                        finishFieldSample();
                    }
                };

        currentWorker.execute();
    }

    private void finishFieldSample() {
        finishSample("field");

        if (currentWorker != null) {
            return;
        }

        // currentWorker is null after finishSample clears it — rows are in tableModel
        int rowCount = tableModel.getRowCount();
        if (rowCount == 0) {
            cardinalityHintLabel.setVisible(false);
            return;
        }

        Map<String, Integer> valuesPerParent = new HashMap<>();
        for (int i = 0; i < rowCount; i++) {
            String parentQid = (String) tableModel.getValueAt(i, 0);
            String value     = (String) tableModel.getValueAt(i, 2);
            if (parentQid != null && value != null && !value.isBlank()) {
                valuesPerParent.merge(parentQid, 1, Integer::sum);
            }
        }

        int maxValues = valuesPerParent.values().stream()
                .mapToInt(Integer::intValue).max().orElse(0);
        long multiParents = valuesPerParent.values().stream()
                .filter(n -> n > 1).count();

        FieldCardinality detected =
                maxValues > 1 ? FieldCardinality.COLLECTION : FieldCardinality.SINGLE;

        String hint = detected == FieldCardinality.COLLECTION
                ? "Detected: Collection (" + multiParents + " of "
                        + valuesPerParent.size() + " sampled entities have multiple values)"
                : "Detected: Single (all sampled entities have at most one value)";

        cardinalityHintLabel.setText(hint + " → applied");
        cardinalityHintLabel.setVisible(true);

        onCardinalitySuggested.accept(detected);
    }

    private List<Parent> loadParentSample(RuleNode parentSample)
            throws Exception {

        String parentSparql =
                RuleTreeQueries.valuesQueryWithoutIncludedFields(parentSample);

        log.accept("Parent sample query\n-------------------\n");
        log.accept(parentSparql + "\n");

        List<Parent> parents =
                new ArrayList<>();

        for (WikidataBinding b : client.query(parentSparql)) {
            if (currentWorker != null && currentWorker.isCancelled()) {
                break;
            }

            String qid =
                    b.qid("value");

            String label =
                    b.label("value");

            if (qid != null && qid.matches("Q\\d+")) {
                parents.add(new Parent(qid, label));
            }
        }

        return parents;
    }

    private void finishSample(String kind) {
        setBusy(false);

        if (currentWorker == null) {
            return;
        }

        if (currentWorker.isCancelled()) {
            statusLabel.setText("Cancelled " + kind + " sample.");
            log.accept("SAMPLE " + kind + " cancelled.\n");
            currentWorker = null;
            return;
        }

        try {
            List<Row> rows =
                    currentWorker.get();

            tableModel.setRowCount(0);

            for (Row row : rows) {
                tableModel.addRow(new Object[]{
                        row.parentQid(),
                        row.parentLabel(),
                        row.value(),
                        row.valueLabel()
                });
            }

            statusLabel.setText(
                    rows.size()
                            + " "
                            + kind
                            + " row"
                            + (rows.size() == 1 ? "" : "s"));

            log.accept("SAMPLE "
                    + kind
                    + " returned rows: "
                    + rows.size()
                    + "\n");
        } catch (Exception ex) {
            statusLabel.setText("Error: " + ex.getMessage());
            log.accept("SAMPLE "
                    + kind
                    + " error: "
                    + ex.getMessage()
                    + "\n");
            ex.printStackTrace();
        } finally {
            currentWorker = null;
        }
    }

    private void cancelSample() {
        if (currentWorker != null && !currentWorker.isDone()) {
            log.accept("Cancelling sample...\n");
            statusLabel.setText("Cancelling sample...");
            currentWorker.cancel(true);
            if (client != null) client.cancelCurrentQuery();
        }
    }

    private void setBusy(boolean busy) {
        setCursor(Cursor.getPredefinedCursor(
                busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    public static RuleNode sampleNode(RuleNode source, int limit) {
        RuleNode s =
                new RuleNode(source.name(), source.itemVar());

        s.sourceQid(source.sourceQid());
        s.sourceLabel(source.sourceLabel());
        s.propertyPid(source.propertyPid());
        s.propertyLabel(source.propertyLabel());
        s.direction(source.direction());
        s.limit(limit);
        s.labelConfig(source.labelConfig());

        s.includedQidsText(source.includedQidsText());
        s.excludedQidsText(source.excludedQidsText());
        s.excludedPredicateObjectsText(source.excludedPredicateObjectsText());

        source.valueFilters().forEach(s::addValueFilter);

        return s;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private record Parent(String qid, String label) {
    }

    private record Row(
            String parentQid,
            String parentLabel,
            String value,
            String valueLabel) {
    }

    private void installLinkBehavior() {
        javax.swing.table.DefaultTableCellRenderer linkRenderer =
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    public java.awt.Component getTableCellRendererComponent(
                            JTable t, Object value, boolean sel, boolean focus, int row, int col) {
                        super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                        String text = value == null ? "" : value.toString();
                        if (!sel && text.matches("[PQ]\\d+"))
                            setForeground(new java.awt.Color(0, 80, 200));
                        else if (!sel)
                            setForeground(t.getForeground());
                        return this;
                    }
                };
        table.getColumnModel().getColumn(0).setCellRenderer(linkRenderer);
        table.getColumnModel().getColumn(2).setCellRenderer(linkRenderer);

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                int viewRow = table.rowAtPoint(e.getPoint());
                int viewCol = table.columnAtPoint(e.getPoint());
                if (viewRow < 0) return;
                Object val = table.getValueAt(viewRow, viewCol);
                String text = val == null ? "" : val.toString();
                if (text.matches("Q\\d+"))
                    openInBrowser("https://www.wikidata.org/wiki/" + text);
                else if (text.matches("P\\d+"))
                    openInBrowser("https://www.wikidata.org/wiki/Property:" + text);
                else if (text.startsWith("http"))
                    openInBrowser(text);
            }
        });
        table.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int viewRow = table.rowAtPoint(e.getPoint());
                int viewCol = table.columnAtPoint(e.getPoint());
                boolean link = false;
                if (viewRow >= 0 && (viewCol == 0 || viewCol == 2)) {
                    Object val = table.getValueAt(viewRow, viewCol);
                    String text = val == null ? "" : val.toString();
                    link = text.matches("[PQ]\\d+") || text.startsWith("http");
                }
                table.setCursor(link
                        ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                        : java.awt.Cursor.getDefaultCursor());
            }
        });
    }

    private static void openInBrowser(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
        } catch (Exception ignored) {}
    }
}
