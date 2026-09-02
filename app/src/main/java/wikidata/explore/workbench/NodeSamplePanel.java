package wikidata.explore.workbench;

import wikidata.WikidataIds;

import wikidata.explore.model.FieldSampleContext;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.query.result.ClassSampleResult;
import wikidata.explore.query.logical.SampleFieldQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import work.Query;

public class NodeSamplePanel extends JPanel {

    public static final int SAMPLE_LIMIT = 8;

    private SwingQueryRunner queryRunner;

    private Runnable applyEdits = () -> {};

    private Supplier<Query<ClassSampleResult>> classSampleSupplier =
            () -> null;
    private Supplier<String> classSampleUnavailableReason =
            () -> "Select the class to sample instances.";

    private Supplier<FieldSampleContext> fieldSampleSupplier =
            () -> null;

    private Consumer<String> log =
            s -> {};

    private Consumer<FieldCardinality> onCardinalitySuggested =
            c -> {};

    private boolean wired;

    private final JButton classSampleButton =
            new JButton("Sample class instances");

    private final JButton fieldSampleButton =
            new JButton("Sample selected field");

    private Runnable onSampleFailed = () -> { };
    private final JLabel statusLabel =
            new JLabel(" ");

    private final JLabel contextLabel =
            new JLabel(" ");

    private final JLabel cardinalityHintLabel =
            new JLabel("");

    private final DefaultTableModel tableModel =
            new DefaultTableModel(
                    new Object[]{
                            "Parent QID",
                            "Parent label",
                            "Value QID/value",
                            "Value label"
                    },
                    0) {
                @Override
                public boolean isCellEditable(int r, int c) {
                    return false;
                }
            };

    private final JTable table =
            new JTable(tableModel);

    private final JLabel classResultNotice =
            new JLabel("Class samples open in the Instances window.", SwingConstants.CENTER);

    private Consumer<ClassSampleResult> onClassSample = ignored -> { };

    private final JPanel resultCards = new JPanel(new CardLayout());

    private final JTextArea sparqlArea =
            new JTextArea();

    public NodeSamplePanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    public void setQueryRunner(SwingQueryRunner queryRunner) {
        this.queryRunner = queryRunner;
        wireQueries();
    }

    public void setApplyEdits(Runnable applyEdits) {
        this.applyEdits =
                applyEdits == null ? () -> {} : applyEdits;
    }

    public void setClassSampleSupplier(Supplier<Query<ClassSampleResult>> supplier) {
        this.classSampleSupplier =
                supplier == null ? () -> null : supplier;
    }

    public void onClassSample(Consumer<ClassSampleResult> consumer) {
        onClassSample = consumer == null ? ignored -> { } : consumer;
    }

    public void setClassSampleUnavailableReason(Supplier<String> supplier) {
        classSampleUnavailableReason = supplier == null
                ? () -> "Class sampling is unavailable." : supplier;
    }

    public void setFieldSampleSupplier(
            Supplier<FieldSampleContext> supplier) {

        this.fieldSampleSupplier =
                supplier == null ? () -> null : supplier;
    }

    public void log(Consumer<String> log) {
        this.log =
                log == null ? s -> {} : log;
    }

    /**
     * Invoked when a sample fails. A field sample may be started while this panel is
     * not the visible tab, and its failure is reported on a status label the reader
     * cannot see; the owner uses this to bring the panel forward for the one case
     * where interrupting is the point.
     */
    public void onSampleFailed(Runnable callback) {
        onSampleFailed = callback == null ? () -> { } : callback;
    }

    public void onCardinalitySuggested(Consumer<FieldCardinality> c) {
        this.onCardinalitySuggested =
                c == null ? card -> {} : c;
    }

    private void buildUi() {
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        installLinkBehavior();

        sparqlArea.setEditable(false);
        sparqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        JTabbedPane tabs =
                new JTabbedPane();

        resultCards.add(classResultNotice, "class");
        resultCards.add(new JScrollPane(table), "field");
        tabs.addTab("Results", resultCards);
        tabs.addTab("SPARQL", new JScrollPane(sparqlArea));

        contextLabel.setFont(contextLabel.getFont().deriveFont(Font.BOLD));

        JPanel top =
                new JPanel(new BorderLayout(4, 2));

        JPanel buttonRow =
                new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));

        buttonRow.add(classSampleButton);
        buttonRow.add(fieldSampleButton);
        buttonRow.add(statusLabel);
        buttonRow.add(cardinalityHintLabel);

        JLabel hint = new JLabel(
                "<html>Pulls a few instances of the selected class (or a chosen "
                + "field) and shows their real values — to eyeball the data and "
                + "to detect a field's <b>cardinality</b> (single vs. list) before "
                + "you commit to it.</html>");
        hint.setFont(hint.getFont().deriveFont(java.awt.Font.ITALIC));

        top.add(contextLabel, BorderLayout.NORTH);
        top.add(buttonRow, BorderLayout.CENTER);
        top.add(hint, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);

        classSampleButton.setEnabled(false);
        fieldSampleButton.setEnabled(false);
        cardinalityHintLabel.setVisible(false);
    }

    private void wireQueries() {
        if (wired || queryRunner == null) {
            return;
        }

        wired = true;

        queryRunner.wireButton(
                classSampleButton,
                this::acceptClassSample,
                this::buildClassSampleQuery,
                ex -> {
                    statusLabel.setText("Class sample failed.");
                    log.accept("SAMPLE class error: "
                                       + ex.getMessage()
                                       + "\n");
                });

        queryRunner.wireButton(
                fieldSampleButton,
                this::acceptFieldSample,
                this::buildFieldSampleQuery,
                ex -> {
                    onSampleFailed.run();
                    statusLabel.setText("Field sample failed.");
                    log.accept("SAMPLE field error: "
                                       + ex.getMessage()
                                       + "\n");
                });

        updateButtonState();
    }

    public void triggerFieldSample() {
        fieldSampleButton.doClick();
    }

    private Query<ClassSampleResult> buildClassSampleQuery() {
        applyEdits.run();
        Query<ClassSampleResult> query = classSampleSupplier.get();
        if (query == null) {
            String reason = classSampleUnavailableReason.get();
            statusLabel.setText(reason);
            log.accept("SAMPLE class skipped: " + reason + "\n");
            return null;
        }
        contextLabel.setText("Class instances: "
                + query.parameters().getOrDefault("class", "?"));
        ((CardLayout) resultCards.getLayout()).show(resultCards, "class");
        cardinalityHintLabel.setVisible(false);
        statusLabel.setText("Running class sample...");
        sparqlArea.setText("");

        return query;
    }

    private SampleFieldQuery buildFieldSampleQuery() {
        applyEdits.run();

        FieldSampleContext context =
                fieldSampleSupplier.get();

        if (context == null || context.field() == null) {
            statusLabel.setText("Select a field to sample field values.");
            log.accept("SAMPLE field skipped: no field selected.\n");
            return null;
        }

        RuleIncludedField includedField =
                RuleTreeCompiler.compileField(context.field());

        if (includedField == null) {
            statusLabel.setText("Selected field has no Wikidata property.");
            log.accept("SAMPLE field skipped: selected field has no property.\n");
            return null;
        }

        String ownerName =
                context.ownerClass().className();

        String fieldPid =
                includedField.propertyPid() != null
                        ? includedField.propertyPid()
                        : "?";

        String propLabel =
                includedField.propertyLabel() != null
                        && !includedField.propertyLabel().isBlank()
                        ? includedField.propertyLabel()
                        : context.field().name();

        contextLabel.setText(
                ownerName
                        + "  —  "
                        + fieldPid
                        + " · "
                        + propLabel
                        + "  →  value");

        tableModel.setColumnIdentifiers(new Object[]{
                ownerName + " QID",
                ownerName,
                fieldPid + " · " + propLabel,
                propLabel + " label"
        });

        tableModel.setRowCount(0);
        ((CardLayout) resultCards.getLayout()).show(resultCards, "field");
        cardinalityHintLabel.setVisible(false);
        statusLabel.setText("Sampling field values...");
        sparqlArea.setText("");

        return new SampleFieldQuery(context, SAMPLE_LIMIT);
    }

    void acceptClassSample(ClassSampleResult result) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(
                    (result == null ? 0 : result.size()) + " sampled instance(s)"
                            + (result != null && result.truncated() ? "; more available" : ""));
            if (result != null) onClassSample.accept(result);
        });
    }

    private void acceptFieldSample(TableQueryResult result) {
        SwingUtilities.invokeLater(() -> {
            fillTable(result);
            statusLabel.setText(
                    result.size()
                            + " field row"
                            + (result.size() == 1 ? "" : "s"));

            suggestCardinalityFromTable();
        });
    }

    private void fillTable(TableQueryResult result) {
        tableModel.setRowCount(0);

        if (result == null) {
            return;
        }

        if (result.columns() != null
                && result.columns().size() >= 4) {
            tableModel.setColumnIdentifiers(
                    result.columns().toArray());
        }

        for (List<Object> row : result.rows()) {
            tableModel.addRow(new Object[]{
                    value(row, 0),
                    value(row, 1),
                    value(row, 2),
                    value(row, 3)
            });
        }
    }

    private static String value(List<Object> row, int index) {
        if (row == null || index >= row.size()) {
            return "";
        }

        Object v = row.get(index);
        return v == null ? "" : String.valueOf(v);
    }

    private void suggestCardinalityFromTable() {
        int rowCount = tableModel.getRowCount();

        if (rowCount == 0) {
            cardinalityHintLabel.setVisible(false);
            return;
        }

        Map<String, Integer> valuesPerParent =
                new HashMap<>();

        for (int i = 0; i < rowCount; i++) {
            String parentQid =
                    String.valueOf(tableModel.getValueAt(i, 0));

            String value =
                    String.valueOf(tableModel.getValueAt(i, 2));

            if (parentQid != null
                    && value != null
                    && !value.isBlank()) {
                valuesPerParent.merge(
                        parentQid,
                        1,
                        Integer::sum);
            }
        }

        int maxValues =
                valuesPerParent.values().stream()
                               .mapToInt(Integer::intValue)
                               .max()
                               .orElse(0);

        long multiParents =
                valuesPerParent.values().stream()
                               .filter(n -> n > 1)
                               .count();

        FieldCardinality detected =
                maxValues > 1
                        ? FieldCardinality.COLLECTION
                        : FieldCardinality.SINGLE;

        String hint =
                detected == FieldCardinality.COLLECTION
                        ? "Detected: Collection ("
                          + multiParents
                          + " of "
                          + valuesPerParent.size()
                          + " sampled entities have multiple values)"
                        : "Detected: Single (all sampled entities have at most one value)";

        cardinalityHintLabel.setText(hint + " → applied");
        cardinalityHintLabel.setVisible(true);

        onCardinalitySuggested.accept(detected);
    }

    private void updateButtonState() {
        boolean enabled =
                queryRunner != null;

        classSampleButton.setEnabled(enabled);
        fieldSampleButton.setEnabled(enabled);
    }

    private void installLinkBehavior() {
        javax.swing.table.DefaultTableCellRenderer linkRenderer =
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    public java.awt.Component getTableCellRendererComponent(
                            JTable t,
                            Object value,
                            boolean sel,
                            boolean focus,
                            int row,
                            int col) {

                        super.getTableCellRendererComponent(
                                t,
                                value,
                                sel,
                                focus,
                                row,
                                col);

                        String text =
                                value == null ? "" : value.toString();

                        if (!sel && WikidataIds.isId(text)) {
                            setForeground(new java.awt.Color(0, 80, 200));
                        } else if (!sel) {
                            setForeground(t.getForeground());
                        }

                        return this;
                    }
                };

        table.getColumnModel().getColumn(0).setCellRenderer(linkRenderer);
        table.getColumnModel().getColumn(2).setCellRenderer(linkRenderer);

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int viewRow =
                        table.rowAtPoint(e.getPoint());

                int viewCol =
                        table.columnAtPoint(e.getPoint());

                if (viewRow < 0) {
                    return;
                }

                Object val =
                        table.getValueAt(viewRow, viewCol);

                String text =
                        val == null ? "" : val.toString();

                if (WikidataIds.isQid(text)) {
                    openInBrowser("https://www.wikidata.org/wiki/" + text);
                } else if (WikidataIds.isPid(text)) {
                    openInBrowser("https://www.wikidata.org/wiki/Property:" + text);
                } else if (text.startsWith("http")) {
                    openInBrowser(text);
                }
            }
        });

        table.addMouseMotionListener(
                new java.awt.event.MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(java.awt.event.MouseEvent e) {
                        int viewRow =
                                table.rowAtPoint(e.getPoint());

                        int viewCol =
                                table.columnAtPoint(e.getPoint());

                        boolean link = false;

                        if (viewRow >= 0
                                && (viewCol == 0 || viewCol == 2)) {
                            Object val =
                                    table.getValueAt(viewRow, viewCol);

                            String text =
                                    val == null ? "" : val.toString();

                            link =
                                    WikidataIds.isId(text)
                                            || text.startsWith("http");
                        }

                        table.setCursor(link
                                                ? java.awt.Cursor.getPredefinedCursor(
                                java.awt.Cursor.HAND_CURSOR)
                                                : java.awt.Cursor.getDefaultCursor());
                    }
                });
    }

    private static void openInBrowser(String url) {
        objectview.utils.BrowserLauncher.open(url);
    }
}
