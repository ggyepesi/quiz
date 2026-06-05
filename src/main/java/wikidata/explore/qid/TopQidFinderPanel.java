package wikidata.explore.qid;

import aux.GridBagUtils;
import quiz.QuizablePanelConfig;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.query.WikidataQueryBuilder;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TopQidFinderPanel extends JPanel {

    private final WikidataSparqlClient client;
    private final SparqlLogPanel logPanel;

    private final JTextField searchField =
            new JTextField("constellation", 34);

    private final JSpinner limitSpinner =
            new JSpinner(new SpinnerNumberModel(30, 1, 200, 10));

    private final JCheckBox exactAlsoBox =
            new JCheckBox("Exact label/alias too", true);

    private final JButton searchButton =
            new JButton("Find QIDs");

    private final JButton cancelButton =
            new JButton("Cancel");

    private final JButton clearLogButton =
            new JButton("Clear log");

    private final JLabel statusLabel =
            new JLabel(" ");

    private final JProgressBar progressBar =
            new JProgressBar();

    private final SearchableQuizableResultPanel<TopQidResult> resultPanel =
            new SearchableQuizableResultPanel<>(
                    TopQidResult.class,
                    resultConfig());

    private java.util.function.Consumer<TopQidResult> selectedConsumer =
            r -> {};

    private SwingWorker<List<TopQidResult>, Void> currentWorker;
    private long currentStartMillis;

    public TopQidFinderPanel(
            WikidataSparqlClient client,
            SparqlLogPanel logPanel) {

        super(new BorderLayout(6, 6));

        this.client = client;
        this.logPanel = logPanel;

        buildUi();
        wireActions();
    }

    public void onUseSelected(
            java.util.function.Consumer<TopQidResult> selectedConsumer) {

        this.selectedConsumer =
                selectedConsumer == null ? r -> {} : selectedConsumer;
    }

    public TopQidResult selected() {
        return resultPanel.selected();
    }

    private static QuizablePanelConfig resultConfig() {
        QuizablePanelConfig config =
                QuizablePanelConfig.of(TopQidResult.class);

        config.setAllFields(false);
        config.setThumb(false);
        config.setAddListener(false);
        config.addField("name", QuizablePanelConfig.leaf());
        config.addField("qid", QuizablePanelConfig.leaf());
        config.addField("description", QuizablePanelConfig.leaf());

        return config;
    }

    private void buildUi() {
        add(buildSearchPanel(), BorderLayout.NORTH);
        add(resultPanel, BorderLayout.CENTER);
        add(buildStatusPanel(), BorderLayout.SOUTH);

        resultPanel.setDetailsButtonText("Details...");
        resultPanel.setOpenButtonText("Open selected");
        resultPanel.setUseButtonText("Use selected QID");

        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
        cancelButton.setEnabled(false);
    }

    private JComponent buildSearchPanel() {
        JPanel top =
                new JPanel(new GridBagLayout());

        int x = 0;

        top.add(
                new JLabel("Name / description:"),
                GridBagUtils.gbc(
                        x++,
                        0,
                        0.0,
                        0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.NONE,
                        new Insets(4, 4, 4, 4)));

        top.add(
                searchField,
                GridBagUtils.gbc(
                        x++,
                        0,
                        1.0,
                        0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.HORIZONTAL,
                        new Insets(4, 4, 4, 4)));

        top.add(
                new JLabel("Limit:"),
                GridBagUtils.gbc(
                        x++,
                        0,
                        0.0,
                        0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.NONE,
                        new Insets(4, 4, 4, 4)));

        top.add(
                limitSpinner,
                GridBagUtils.gbc(
                        x++,
                        0,
                        0.0,
                        0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.NONE,
                        new Insets(4, 4, 4, 4)));

        top.add(
                exactAlsoBox,
                GridBagUtils.gbc(
                        x++,
                        0,
                        0.0,
                        0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.NONE,
                        new Insets(4, 4, 4, 4)));

        top.add(
                searchButton,
                GridBagUtils.gbc(
                        x++,
                        0,
                        0.0,
                        0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.NONE,
                        new Insets(4, 4, 4, 4)));

        top.add(
                cancelButton,
                GridBagUtils.gbc(
                        x++,
                        0,
                        0.0,
                        0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.NONE,
                        new Insets(4, 4, 4, 4)));

        top.add(
                clearLogButton,
                GridBagUtils.gbc(
                        x,
                        0,
                        0.0,
                        0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.NONE,
                        new Insets(4, 4, 4, 4)));

        return top;
    }

    private JComponent buildStatusPanel() {
        JPanel panel =
                new JPanel(new BorderLayout(4, 4));

        JPanel right =
                new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));

        right.add(progressBar);

        panel.add(statusLabel, BorderLayout.CENTER);
        panel.add(right, BorderLayout.EAST);

        return panel;
    }

    private void wireActions() {
        searchButton.addActionListener(e -> search());
        searchField.addActionListener(e -> search());
        cancelButton.addActionListener(e -> cancelCurrentQuery());
        clearLogButton.addActionListener(e -> logPanel.clear());

        resultPanel.setDetailsAction(this::showDetails);
        resultPanel.setOpenAction(this::openSelected);
        resultPanel.setUseAction(this::useSelected);
    }

    public void search() {
        String text =
                searchField.getText().trim();

        if (text.isBlank()) {
            return;
        }

        if (currentWorker != null && !currentWorker.isDone()) {
            currentWorker.cancel(true);
        }

        int limit =
                ((Number) limitSpinner.getValue()).intValue();

        setBusy(true);
        statusLabel.setText("Waiting for SPARQL reply...");

        currentStartMillis =
                System.currentTimeMillis();

        currentWorker =
                new SwingWorker<>() {
                    @Override
                    protected List<TopQidResult> doInBackground()
                            throws Exception {

                        List<TopQidResult> rows =
                                new ArrayList<>();

                        if (exactAlsoBox.isSelected()) {
                            String exact =
                                    WikidataQueryBuilder.exactLabelOrAlias(
                                            text,
                                            limit);

                            logPanel.logQuery(
                                    "Exact label/alias QID search",
                                    exact);

                            List<TopQidResult> exactRows =
                                    runQuery(exact);

                            if (isCancelled()) {
                                return rows;
                            }

                            logPanel.logSummary(
                                    "Exact label/alias QID search",
                                    exactRows.size());

                            rows.addAll(exactRows);
                        }

                        String search =
                                WikidataQueryBuilder.entitySearch(
                                        text,
                                        limit);

                        logPanel.logQuery("Entity QID search", search);

                        List<TopQidResult> searchRows =
                                runQuery(search);

                        if (isCancelled()) {
                            return rows;
                        }

                        logPanel.logSummary(
                                "Entity QID search",
                                searchRows.size());

                        rows.addAll(searchRows);

                        return dedupe(rows, limit);
                    }

                    @Override
                    protected void done() {
                        long millis =
                                System.currentTimeMillis()
                                        - currentStartMillis;

                        setBusy(false);

                        if (isCancelled()) {
                            statusLabel.setText("Cancelled after "
                                                        + millis
                                                        + " ms");

                            logPanel.logCancelled("QID search", millis);
                            return;
                        }

                        try {
                            List<TopQidResult> rows =
                                    get();

                            resultPanel.setRows(rows);
                            statusLabel.setText("Found QIDs: "
                                                        + rows.size()
                                                        + " ("
                                                        + millis
                                                        + " ms)");
                            logPanel.logSummary("QID search total",
                                                rows.size(),
                                                millis);
                        } catch (Exception ex) {
                            statusLabel.setText("ERROR after "
                                                        + millis
                                                        + " ms: "
                                                        + ex.getMessage());
                            ex.printStackTrace();
                            logPanel.logError("QID search", ex);
                        }
                    }
                };

        currentWorker.execute();
    }

    private void cancelCurrentQuery() {
        if (currentWorker != null && !currentWorker.isDone()) {
            currentWorker.cancel(true);
            statusLabel.setText("Cancelling...");
            cancelButton.setEnabled(false);
        }
    }

    private List<TopQidResult> runQuery(String sparql)
            throws Exception {

        List<TopQidResult> rows =
                new ArrayList<>();

        for (WikidataBinding b : client.query(sparql)) {
            if (currentWorker != null && currentWorker.isCancelled()) {
                break;
            }

            String qid =
                    b.qid("item");

            String label =
                    b.label("item");

            String desc =
                    b.value("itemDescription");

            if (qid != null && qid.matches("Q\\d+")) {
                rows.add(new TopQidResult(qid, label, desc));
            }
        }

        return rows;
    }

    private List<TopQidResult> dedupe(
            List<TopQidResult> input,
            int limit) {

        List<TopQidResult> out =
                new ArrayList<>();

        Set<String> seen =
                new LinkedHashSet<>();

        for (TopQidResult r : input) {
            if (seen.add(r.qid())) {
                out.add(r);
            }

            if (out.size() >= limit) {
                break;
            }
        }

        return out;
    }

    private void showDetails(TopQidResult result) {
        TopQidDetailsFrame frame =
                new TopQidDetailsFrame(client, result, logPanel);

        frame.setVisible(true);
    }

    private void openSelected(TopQidResult result) {
        try {
            Desktop.getDesktop().browse(new URI(result.wikidataUrl()));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void useSelected(TopQidResult result) {
        selectedConsumer.accept(result);
        statusLabel.setText("Selected: " + result);
    }

    private void setBusy(boolean busy) {
        searchButton.setEnabled(!busy);
        cancelButton.setEnabled(busy);
        searchField.setEnabled(!busy);
        limitSpinner.setEnabled(!busy);
        exactAlsoBox.setEnabled(!busy);
        resultPanel.setActionsEnabled(!busy);
        progressBar.setIndeterminate(busy);
        progressBar.setVisible(busy);
        searchButton.setText(busy ? "Searching..." : "Find QIDs");
    }
}
