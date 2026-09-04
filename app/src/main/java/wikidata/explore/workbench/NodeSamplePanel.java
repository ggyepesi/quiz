package wikidata.explore.workbench;

import wikidata.WikidataIds;

import wikidata.explore.query.swing.QueryObjectResultPanel;
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

    /**
     * One button, because there is one question: sample what is selected.
     *
     * <p>Two buttons made the reader work out which one their selection matched, and each
     * guessed at its own enablement — on an aggregate class both offered themselves, one
     * answering "not implemented yet" and the other "select a field", and pressing either
     * left the pair in a state neither had decided. Its text says which sample it will
     * take, so the selection-dependent part is stated rather than inferred.
     */
    private final JButton sampleButton =
            new JButton("Sample");

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

    /**
     * A class sample has ONE home, and it is here.
     *
     * <p>Rendered by the shared Viewable renderer — the same one every other view of
     * generated objects uses, so a sampled instance looks like a generated one. It was
     * shown here AND in a window of its own for a while: the same objects twice, in two
     * sizes, disagreeing about how they looked, with the reader left to decide which to
     * believe. Beside the class's own editor and its explanation is where the sample is
     * being read, so that is where it is drawn.
     */
    private final QueryObjectResultPanel classResultPanel = new QueryObjectResultPanel();

    // (5) A failure is not a status line. Compilation refuses with the model's whole
    // validation report, and "Class sample failed." threw all of it away.
    private final JTextArea failureArea = new JTextArea();

    private Consumer<ClassSampleResult> onClassSample = ignored -> { };

    /** What the shown result is a sample OF, and whether one is shown at all. */
    private Object shownSubject;
    private boolean hasSubject;
    private boolean resultShown;

    /** The runner's own broadcast, which is what disables every other run button. */
    private boolean runnerBusy;

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

        failureArea.setEditable(false);
        failureArea.setLineWrap(true);
        failureArea.setWrapStyleWord(true);
        resultCards.add(classResultPanel, "class");
        resultCards.add(new JScrollPane(failureArea), "failure");
        resultCards.add(new JScrollPane(table), "field");
        tabs.addTab("Results", resultCards);
        tabs.addTab("SPARQL", new JScrollPane(sparqlArea));

        contextLabel.setFont(contextLabel.getFont().deriveFont(Font.BOLD));

        JPanel top =
                new JPanel(new BorderLayout(4, 2));

        JPanel buttonRow =
                new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));

        buttonRow.add(sampleButton);
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

        sampleButton.setEnabled(false);
        cardinalityHintLabel.setVisible(false);
    }

    private void wireQueries() {
        if (wired || queryRunner == null) {
            return;
        }

        wired = true;

        queryRunner.wireButton(
                sampleButton,
                this::acceptSample,
                this::buildSampleQuery,
                ex -> {
                    onSampleFailed.run();
                    showFailure("Sample failed", ex);
                });

        // Two conditions, two owners, and neither may overwrite the other. The runner
        // owns "is something running" — it disables every run button and enables Cancel
        // — and this panel owns "can THIS selection be sampled". Its listeners fire
        // after the blanket toggle, so re-asking availability there is what stops a run
        // ENDING anywhere in the workbench from enabling a button whose selection
        // cannot be sampled. Availability must therefore honour the run state too, or
        // the same listener re-enables Sample in the middle of its own run.
        queryRunner.onRunningChanged(running -> {
            runnerBusy = running;
            refreshAvailability();
        });

        refreshAvailability();
    }

    /** Sample what is selected now — used after a field has just been selected for it. */
    public void triggerSample() {
        sampleButton.doClick();
    }

    /**
     * The sample the current selection calls for: a field's values when a field is
     * selected, otherwise the class's instances.
     *
     * <p>Field first because it is the more specific selection — a selected field always
     * has a declaring class, so asking the class first would mean a field could never be
     * sampled at all.
     */
    private Query<Object> buildSampleQuery() {
        applyEdits.run();
        if (fieldSampleSupplier.get() != null) return erased(buildFieldSampleQuery());
        return erased(buildClassSampleQuery());
    }

    private void acceptSample(Object result) {
        if (result instanceof ClassSampleResult classSample) acceptClassSample(classSample);
        else if (result instanceof TableQueryResult rows) acceptFieldSample(rows);
    }

    /** The same query, seen as producing whatever it produces. */
    @SuppressWarnings("unchecked")
    private static <R> Query<Object> erased(Query<R> query) {
        if (query == null) return null;
        return new Query<>() {
            @Override public String purpose() { return query.purpose(); }
            @Override public String skeleton() { return query.skeleton(); }
            @Override public String queryType() { return query.queryType(); }
            @Override public String description() { return query.description(); }
            @Override public java.util.Map<String, String> parameters() {
                return query.parameters();
            }
            @Override public Object execute(work.QueryContext context) throws Exception {
                return query.execute(context);
            }
            @Override public int rowCount(Object result) {
                return query.rowCount((R) result);
            }
            @Override public String summary(Object result) {
                return query.summary((R) result);
            }
        };
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
            resultShown = true;
            if (result == null) return;
            if (result.instances() != null) {
                classResultPanel.accept(result.instances());
                ((CardLayout) resultCards.getLayout()).show(resultCards, "class");
            }
            onClassSample.accept(result);
        });
    }

    /**
     * Says what went wrong, in full.
     *
     * <p>Sampling compiles the model first, so its usual failure is the validation
     * report — several lines naming the class and what it is missing. "Class sample
     * failed." reduced that to three words and put the rest in a log the reader was not
     * looking at, which is how sampling OfficeHolding came to say nothing about the
     * subject it has not declared.
     */
    private void showFailure(String what, Throwable failure) {
        String detail = failure == null || failure.getMessage() == null
                ? "No detail available." : failure.getMessage().trim();
        statusLabel.setText(what + " — " + firstLine(detail));
        resultShown = true;
        failureArea.setText(detail);
        failureArea.setCaretPosition(0);
        ((CardLayout) resultCards.getLayout()).show(resultCards, "failure");
        log.accept("SAMPLE error: " + detail + "\n");
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String first = newline < 0 ? text : text.substring(0, newline);
        return first.isBlank() ? "see Results" : first;
    }

    private void acceptFieldSample(TableQueryResult result) {
        SwingUtilities.invokeLater(() -> {
            resultShown = true;
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

    /**
     * Each button is enabled only where it can do something, and a disabled one says
     * why on hover.
     *
     * <p>Both used to be enabled whenever a query runner existed, so pressing one with
     * nothing selected — or on a class that cannot be sampled — was answered with a
     * refusal after the fact. The reasons already existed; nothing asked them.
     */
    /**
     * What the panel is now about.
     *
     * <p>A result outlives its subject only until the selection moves. Rows sampled from
     * one class stayed on screen when another was selected — sitting beside THAT class's
     * editor and explanation, where they read as its instances, with nothing saying
     * otherwise because nothing tracked what they were a sample of. Selecting is an
     * inspection: it clears what no longer describes the selection, and produces nothing.
     */
    public void showSubject(Object subject) {
        boolean changed = !hasSubject || !java.util.Objects.equals(subject, shownSubject);
        shownSubject = subject;
        hasSubject = true;
        if (changed) clearResult();
        refreshAvailability();
    }

    private void clearResult() {
        resultShown = false;
        classResultPanel.clear();
        tableModel.setRowCount(0);
        failureArea.setText("");
        sparqlArea.setText("");
        cardinalityHintLabel.setVisible(false);
        contextLabel.setText(" ");
        ((CardLayout) resultCards.getLayout()).show(resultCards, "class");
    }

    public void refreshAvailability() {
        // ONE question, asked in the order the button answers it: a selected field is
        // sampled as a field, anything else as a class. Two independent enablement rules
        // is what let an aggregate offer both buttons and honour neither.
        boolean field = queryRunner != null && fieldSampleSupplier.get() != null;

        String reason;
        if (queryRunner == null) {
            reason = "Sampling needs a query runner.";
        } else if (runnerBusy) {
            // Sample belongs to the group a run disables, and Cancel is what is offered
            // instead. Asking again while the answer is being fetched is not a second
            // sample, it is the same one twice.
            reason = "Something is already running \u2014 cancel it or wait.";
        } else if (field) {
            reason = "";
        } else {
            // Whether a class can be sampled is answered by BUILDING its query, because
            // that is the same question the button asks a moment later. A reason string
            // alone could not answer it: classSampleUnavailableReason only ever returns a
            // reason, since it exists to explain a refusal that has already happened.
            try {
                reason = classSampleSupplier.get() == null
                        ? classSampleUnavailableReason.get() : "";
            } catch (RuntimeException refused) {
                // An invalid model refuses at compile. That is a real reason not to
                // offer the button, and the message is the validation report.
                reason = refused.getMessage() == null
                        ? "This class cannot be sampled." : refused.getMessage();
            }
        }

        boolean canSample = reason.isBlank();
        sampleButton.setText(canSample && field
                ? "Sample field values" : "Sample class instances");
        sampleButton.setEnabled(canSample);
        sampleButton.setToolTipText(canSample
                ? (field ? "Read this field's real values, and detect its cardinality."
                        : "Produce this class's instances and show them here.")
                : reason);

        // With nothing shown, the status line is the only place saying why. A reason that
        // lives only in a disabled button's tooltip is a reason the reader has to hunt for.
        if (!resultShown) {
            statusLabel.setText(canSample
                    ? "Press " + sampleButton.getText() + "." : reason);
        }
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
