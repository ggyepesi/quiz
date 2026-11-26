package domain.datasource.discovery.ui;

import objectview.Viewable;
import objectview.view.SearchableView;
import domain.transform.discovery.WikidataStatementDiscoveryQuery;
import domain.transform.discovery.WikidataStatementDiscoveryResult;
import domain.transform.ui.BoundedInstanceSelection;
import domain.transform.ui.TransformController;
import domain.runtime.WikidataDynamicObject;
import domain.workbench.execution.ui.SwingQueryRunner;
import domain.workbench.execution.ui.SwingQuerySession;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * The Wikidata-statements tab of the shared Discovery workbench. It reads the same
 * explicit {@link BoundedInstanceSelection} as every other source tab; no separate
 * sample dialog or selected-instance list exists in DataSourceApp.
 */
public final class WikidataStatementDiscoveryPanel extends JPanel
        implements AutoCloseable {
    public static final String FETCH_STATEMENTS = "wikidata.fetch-statements";
    private final TransformController domain;
    private final BoundedInstanceSelection selection;
    private final SwingQuerySession queries;
    private final SwingQueryRunner runner;

    private final JButton fetch = new JButton(
            "Fetch statements and qualifiers for 0 selected instances");
    private final JButton cancel = new JButton("Cancel");
    private final JButton logs = new JButton("Query logs");
    private final JLabel status = new JLabel(
            "Select exact instances, then fetch their Wikidata statements and qualifiers.");
    private final JTabbedPane resultTabs =
            new domain.workbench.ui.WorkbenchResultViews();
    private DiscoveryResultListener resultListener = DiscoveryResultListener.NONE;
    private boolean closed;

    public WikidataStatementDiscoveryPanel(
            TransformController domain, BoundedInstanceSelection selection) {
        this(domain, selection, null);
    }

    WikidataStatementDiscoveryPanel(TransformController domain,
            BoundedInstanceSelection selection, SwingQuerySession hostQueries) {
        super(new BorderLayout(6, 6));
        queries = hostQueries == null ? SwingQuerySession.openWorkbench()
                : hostQueries.companion();
        runner = queries.runner();
        this.domain = java.util.Objects.requireNonNull(domain);
        this.selection = java.util.Objects.requireNonNull(selection);
        selection.addHost(new BoundedInstanceSelection.Host() {
            @Override public void selectionChanged(String reason) {
                showValues(List.of(), reason, false);
                resultListener.invalidated(FETCH_STATEMENTS);
            }

            @Override public void updateActions() {
                WikidataStatementDiscoveryPanel.this.updateActions();
            }
        });
        build();
        wire();
        showValues(List.of(), status.getText(), false);
        updateActions();
    }

    private void build() {
        JPanel controls = new JPanel(new objectview.utils.swing.WrapLayout(
                FlowLayout.LEFT, 6, 2));
        controls.add(fetch);
        controls.add(cancel);
        controls.add(logs);
        JPanel north = new JPanel(new BorderLayout(4, 3));
        north.add(controls, BorderLayout.NORTH);
        north.add(status, BorderLayout.SOUTH);
        north.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        resultTabs.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        add(north, BorderLayout.NORTH);
        add(resultTabs, BorderLayout.CENTER);
    }

    private void wire() {
        fetch.addActionListener(event -> fetchSelected());
        logs.addActionListener(event -> queries.showLogs(this));
        runner.registerRunButton(fetch);
        runner.registerCancelButton(cancel);
        runner.onRunningChanged(running -> {
            selection.setRunning(this, running);
            updateActions();
        });
    }

    private void fetchSelected() {
        if (!selection.canRun()) return;
        List<String> qids = selection.selected().stream()
                .map(WikidataDynamicObject::qid)
                .filter(qid -> qid != null && !qid.isBlank())
                .distinct().toList();
        if (qids.isEmpty()) return;
        status.setText("Fetching every Wikidata statement and qualifier for exactly "
                + qids.size() + " selected instance(s), in "
                + batches(qids.size()) + " batch request(s) of at most "
                + WikidataStatementDiscoveryQuery.BATCH_SIZE + " QIDs. The project is "
                + "not changed.");
        WikidataStatementDiscoveryQuery query =
                new WikidataStatementDiscoveryQuery(qids);
        runner.run(query,
                summaries -> SwingUtilities.invokeLater(() -> {
                    List<Viewable> loaded = domain.domain().instances().stream()
                            .map(Viewable.class::cast).toList();
                    List<WikidataDynamicObject> values =
                            WikidataStatementDiscoveryResult.materialize(
                                    summaries, loaded);
                    showValues(values, query.summary(summaries)
                            + ". Results are read-only; no project value was changed.",
                            true);
                }),
                failure -> SwingUtilities.invokeLater(() -> {
                    String message = failure == null || failure.getMessage() == null
                            ? "Wikidata statement discovery failed."
                            : failure.getMessage();
                    status.setText("Wikidata statement discovery failed: " + message);
                    JOptionPane.showMessageDialog(this, message,
                            "Wikidata statement discovery failed",
                            JOptionPane.ERROR_MESSAGE);
                }));
    }

    private void showValues(List<WikidataDynamicObject> values, String message,
            boolean publish) {
        resultTabs.removeAll();
        resultTabs.addTab("Statements and qualifiers — " + values.size(),
                SearchableView.builder(values)
                .sample(WikidataStatementDiscoveryResult.unionSample(values))
                .valueLinker(wikidata.ui.WikidataLinks.valueLinker())
                .collapsible(true)
                .emptyMessage("No Wikidata statement discovery has run for this selection.")
                .build());
        status.setText(message);
        if (publish) resultListener.published(FETCH_STATEMENTS, resultTabs, 0);
    }

    public void setResultListener(DiscoveryResultListener listener) {
        resultListener = listener == null ? DiscoveryResultListener.NONE : listener;
    }

    private void updateActions() {
        int count = selection.size();
        boolean running = runner.isRunning();
        boolean bounded = count <= selection.maximum();
        fetch.setEnabled(!running && selection.canRun() && count > 0 && bounded);
        fetch.setText(running ? "Fetching Wikidata statements and qualifiers…"
                : "Fetch statements and qualifiers for " + count
                + " selected instance" + (count == 1 ? "" : "s"));
        fetch.setToolTipText(count == 0
                ? "Select one or more instances in the shared selection, then fetch."
                : !bounded ? "One run accepts at most " + selection.maximum()
                        + " selected instances; remove "
                        + (count - selection.maximum()) + "."
                : "Fetch every Wikidata statement and qualifier for exactly " + count
                + " selected instance(s), in " + batches(count)
                + " batch request(s). Nothing is fetched until this button is pressed.");
    }

    private static int batches(int instances) {
        return (instances + WikidataStatementDiscoveryQuery.BATCH_SIZE - 1)
                / WikidataStatementDiscoveryQuery.BATCH_SIZE;
    }

    public boolean requestStopRun(Component owner) {
        return !runner.isRunning()
                || process.swing.SwingCancellationConfirmation.requestForVisibleRun(
                        owner, runner::cancel);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        queries.close();
    }
}
