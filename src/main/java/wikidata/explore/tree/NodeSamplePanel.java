package wikidata.explore.tree;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
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

    private SwingWorker<List<Row>, Void> currentWorker;

    private final JButton classSampleButton =
            new JButton("Sample class instances");

    private final JButton fieldSampleButton =
            new JButton("Sample selected field");

    private final JButton cancelButton =
            new JButton("Cancel sample");

    private final JLabel statusLabel =
            new JLabel(" ");

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
        boolean enabled = client != null;
        classSampleButton.setEnabled(enabled);
        fieldSampleButton.setEnabled(enabled);
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

    private void buildUi() {
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        sparqlArea.setEditable(false);
        sparqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        JTabbedPane tabs =
                new JTabbedPane();

        tabs.addTab("Results", new JScrollPane(table));
        tabs.addTab("SPARQL", new JScrollPane(sparqlArea));

        JPanel top =
                new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));

        top.add(classSampleButton);
        top.add(fieldSampleButton);
        top.add(cancelButton);
        top.add(statusLabel);

        add(top, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);

        classSampleButton.setEnabled(false);
        fieldSampleButton.setEnabled(false);
        cancelButton.setEnabled(false);

        classSampleButton.addActionListener(e -> runClassSample());
        fieldSampleButton.addActionListener(e -> runFieldSample());
        cancelButton.addActionListener(e -> cancelSample());
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

        tableModel.setRowCount(0);
        statusLabel.setText("Sampling parent objects...");
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
                                        parentSample.labelConfig());

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
                        finishSample("field");
                    }
                };

        currentWorker.execute();
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
        }
    }

    private void setBusy(boolean busy) {
        classSampleButton.setEnabled(!busy && client != null);
        fieldSampleButton.setEnabled(!busy && client != null);
        cancelButton.setEnabled(busy);
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
}
