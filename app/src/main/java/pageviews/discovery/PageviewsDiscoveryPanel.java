package pageviews.discovery;

import datasource.pageviews.PageviewsClient;
import objectview.render.RenderingMode;
import objectview.utils.swing.WrapLayout;
import objectview.view.SearchableView;
import quiz.transform.ui.BoundedInstanceSelection;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.query.swing.SwingQuerySession;
import work.QueryContext;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * The Pageviews tab of the Discovery workbench: how often the selected instances'
 * English Wikipedia articles were read. It shows whether the measure separates well-known
 * from obscure instances before anyone binds it to a field; nothing here changes the model.
 */
public final class PageviewsDiscoveryPanel extends JPanel implements AutoCloseable {
    private static final int DEFAULT_MONTHS = 12;

    private final BoundedInstanceSelection selection;
    private final PageviewsClient client;
    private final java.time.Clock clock;
    private final SwingQuerySession queries = new SwingQuerySession(new QueryContext());
    private final SwingQueryRunner runner = queries.runner();

    private final JSpinner months = new JSpinner(
            new SpinnerNumberModel(DEFAULT_MONTHS, 1, 120, 1));
    private final JButton fetch = new JButton("Read views of 0 selected articles");
    private final JButton cancel = new JButton("Cancel");
    private final JButton logs = new JButton("Query logs");
    private final JLabel status = new JLabel(" ");
    private final JLabel resultStatus = new JLabel(
            "Select instances, then read how often their Wikipedia articles were viewed.");
    private final JPanel results = new JPanel(new BorderLayout());
    private boolean closed;

    public PageviewsDiscoveryPanel(BoundedInstanceSelection selection) {
        this(selection, new PageviewsClient(), java.time.Clock.systemUTC());
    }

    PageviewsDiscoveryPanel(BoundedInstanceSelection selection, PageviewsClient client,
            java.time.Clock clock) {
        super(new BorderLayout(6, 6));
        this.selection = java.util.Objects.requireNonNull(selection);
        this.client = java.util.Objects.requireNonNull(client);
        this.clock = java.util.Objects.requireNonNull(clock);
        selection.addHost(new BoundedInstanceSelection.Host() {
            @Override public void selectionChanged(String reason) { showRows(List.of(), reason); }
            @Override public void updateActions() {
                PageviewsDiscoveryPanel.this.updateActions();
            }
        });
        build();
        wire();
        showRows(List.of(), resultStatus.getText());
        updateActions();
    }

    private void build() {
        JPanel controls = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        controls.add(new JLabel("Complete months:"));
        controls.add(months);
        controls.add(fetch);
        controls.add(cancel);
        controls.add(logs);
        JPanel north = new JPanel(new BorderLayout(4, 3));
        north.add(controls, BorderLayout.NORTH);
        north.add(status, BorderLayout.SOUTH);
        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(resultStatus, BorderLayout.NORTH);
        center.add(results, BorderLayout.CENTER);
        north.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        center.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        add(north, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
    }

    private void wire() {
        fetch.addActionListener(event -> fetchSelected());
        logs.addActionListener(event -> queries.showLogs(this));
        months.addChangeListener(event -> updateActions());
        runner.registerRunButton(fetch);
        runner.registerCancelButton(cancel);
        runner.onRunningChanged(running -> {
            selection.setRunning(this, running);
            updateActions();
        });
    }

    private PageviewsClient.Window window() {
        return PageviewsClient.Window.lastCompleteMonths((Integer) months.getValue(), clock);
    }

    /** The selected instances with an English Wikipedia article; others are counted. */
    private List<WikidataDynamicObject> articles() {
        return selection.selected().stream()
                .filter(value -> !value.enwikiTitle().isBlank()).toList();
    }

    private String description() {
        List<WikidataDynamicObject> articles = articles();
        int missing = selection.size() - articles.size();
        return "Read English Wikipedia views by human readers, " + window() + ", for "
                + articles.size() + " article(s): one request each, about "
                + Math.max(1, Math.round(articles.size() * 0.3)) + " s."
                + (missing == 0 ? "" : " " + missing + " selected instance(s) have no "
                        + "English Wikipedia article correspondence and are not read.")
                + " The project is not changed.";
    }

    private void fetchSelected() {
        List<WikidataDynamicObject> articles = articles();
        if (articles.isEmpty()) return;
        status.setText(description());
        PageviewsDiscoveryQuery query = new PageviewsDiscoveryQuery(
                articles, window(), client);
        runner.run(query,
                rows -> SwingUtilities.invokeLater(() -> showRows(rows,
                        query.summary(rows) + ", " + window()
                                + ", most read first. An article without a record has no "
                                + "value, not zero.")),
                failure -> SwingUtilities.invokeLater(() -> {
                    String message = failure == null || failure.getMessage() == null
                            ? "Pageviews discovery failed." : failure.getMessage();
                    status.setText("Pageviews discovery failed: " + message);
                    JOptionPane.showMessageDialog(this, message,
                            "Pageviews discovery failed", JOptionPane.ERROR_MESSAGE);
                }));
    }

    private void showRows(List<ArticleViews> rows, String message) {
        results.removeAll();
        results.add(SearchableView.builder(rows)
                .type(ArticleViews.class)
                .mode(RenderingMode.TABLE)
                .valueLinker(wikidata.ui.WikidataLinks.valueLinker())
                .emptyMessage("No Pageviews request has run for this selection.")
                .build(), BorderLayout.CENTER);
        results.revalidate();
        results.repaint();
        resultStatus.setText(message);
    }

    private void updateActions() {
        boolean running = runner.isRunning();
        int count = articles().size();
        boolean bounded = count <= PageviewsDiscoveryQuery.MAX_ARTICLES;
        months.setEnabled(!running);
        fetch.setEnabled(!running && count > 0 && bounded);
        fetch.setText(running ? "Reading views…" : "Read views of " + count
                + " selected article" + (count == 1 ? "" : "s"));
        fetch.setToolTipText(count == 0
                ? "Select at least one instance with an English Wikipedia article."
                : !bounded ? "One run is bounded to " + PageviewsDiscoveryQuery.MAX_ARTICLES
                        + " articles; remove " + (count - PageviewsDiscoveryQuery.MAX_ARTICLES)
                        + " from the selection."
                        : description());
    }

    /** Whether running work may stop: keeping it is the default. */
    public boolean requestStopRun(Component owner) {
        return !runner.isRunning()
                || process.swing.SwingCancellationConfirmation.requestForVisibleRun(
                        owner, runner::cancel);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        runner.cancel();
    }
}
