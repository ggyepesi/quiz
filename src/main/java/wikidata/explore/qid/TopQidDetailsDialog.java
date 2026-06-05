package wikidata.explore.qid;

import aux.GridBagUtils;
import quiz.QuizablePanelConfig;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.WikidataProperty;
import wikidata.explore.WikidataPropertyStore;
import wikidata.query.WikidataQueryBuilder;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TopQidDetailsDialog extends JDialog {

    private final WikidataSparqlClient client;
    private final TopQidResult result;
    private final SparqlLogPanel logPanel;

    private final SearchableQuizableResultPanel<TopQidStatement> resultPanel =
            new SearchableQuizableResultPanel<>(
                    TopQidStatement.class,
                    statementConfig());

    private final JLabel statusLabel =
            new JLabel("Loading...");

    private final Map<String, WikidataProperty> propertyByPid =
            new LinkedHashMap<>();

    public TopQidDetailsDialog(
            Window owner,
            WikidataSparqlClient client,
            TopQidResult result,
            SparqlLogPanel logPanel) {

        super(owner,
              "Statements: " + result.getName() + " (" + result.qid() + ")",
              ModalityType.MODELESS);

        this.client = client;
        this.result = result;
        this.logPanel = logPanel;

        setModal(false);
        setAlwaysOnTop(false);

        loadPropertyCache();
        buildUi();
        wireActions();
        loadStatements();

        setSize(1000, 760);
        setLocationRelativeTo(owner);
    }

    private static QuizablePanelConfig statementConfig() {
        QuizablePanelConfig config =
                QuizablePanelConfig.of(TopQidStatement.class);

        config.setAllFields(false);
        config.setThumb(false);
        config.setAddListener(false);
        config.addField("name", QuizablePanelConfig.leaf());
        config.addField("pid", QuizablePanelConfig.leaf());
        config.addField("propertyDescription", QuizablePanelConfig.leaf());
        config.addField("valueDisplay", QuizablePanelConfig.leaf());
        config.addField("valueQid", QuizablePanelConfig.leaf());
        config.addField("rawValue", QuizablePanelConfig.leaf());

        return config;
    }

    private void loadPropertyCache() {
        propertyByPid.clear();

        try {
            WikidataPropertyStore store =
                    new WikidataPropertyStore();

            for (WikidataProperty p : store.read()) {
                propertyByPid.put(p.pid(), p);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            if (logPanel != null) {
                logPanel.logError("Load property cache", ex);
            }
        }
    }

    private void buildUi() {
        setLayout(new BorderLayout(6, 6));

        add(buildHeader(), BorderLayout.NORTH);
        add(resultPanel, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        resultPanel.setDetailsButtonText("Details of value...");
        resultPanel.setOpenButtonText("Open value");
        resultPanel.setUseButtonText("Use value QID");
    }

    private JComponent buildHeader() {
        JPanel header =
                new JPanel(new GridBagLayout());

        JLabel title =
                new JLabel(result.getName() + " (" + result.qid() + ")");

        title.setFont(title.getFont().deriveFont(Font.BOLD));

        JLabel description =
                new JLabel(result.description());

        header.add(
                title,
                GridBagUtils.gbc(
                        0,
                        0,
                        1.0,
                        0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.HORIZONTAL,
                        new Insets(6, 6, 2, 6)));

        header.add(
                description,
                GridBagUtils.gbc(
                        0,
                        1,
                        1.0,
                        0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.HORIZONTAL,
                        new Insets(0, 6, 6, 6)));

        return header;
    }

    private void wireActions() {
        resultPanel.setDetailsAction(this::showStatementValueDetails);
        resultPanel.setOpenAction(this::openStatementValue);
        resultPanel.setUseAction(this::useStatementValue);
    }

    private void loadStatements() {
        statusLabel.setText("Loading statements...");

        SwingWorker<List<TopQidStatement>, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected List<TopQidStatement> doInBackground()
                            throws Exception {

                        List<TopQidStatement> rows =
                                new ArrayList<>();

                        String sparql =
                                WikidataQueryBuilder.outgoingDirectStatements(
                                        result.qid(),
                                        500);

                        if (logPanel != null) {
                            logPanel.logQuery(
                                    "Outgoing statements for "
                                            + result.getName()
                                            + " ("
                                            + result.qid()
                                            + ")",
                                    sparql);
                        }

                        for (WikidataBinding b : client.query(sparql)) {
                            String pid =
                                    b.qid("property");

                            WikidataProperty property =
                                    propertyByPid.get(pid);

                            String propertyLabel =
                                    property == null ? pid : property.label();

                            String propertyDescription =
                                    property == null
                                            ? ""
                                            : property.description();

                            String rawValue =
                                    b.value("value");

                            String valueQid =
                                    b.qid("value");

                            if (valueQid != null
                                    && !valueQid.matches("Q\\d+")) {
                                valueQid = "";
                            }

                            String valueLabel =
                                    b.label("value");

                            rows.add(new TopQidStatement(
                                    pid,
                                    propertyLabel,
                                    propertyDescription,
                                    valueQid,
                                    valueLabel,
                                    rawValue));
                        }

                        return rows;
                    }

                    @Override
                    protected void done() {
                        try {
                            List<TopQidStatement> rows =
                                    get();

                            resultPanel.setRows(rows);
                            statusLabel.setText("Statements: " + rows.size());

                            if (logPanel != null) {
                                logPanel.logSummary(
                                        "Outgoing statements for "
                                                + result.qid(),
                                        rows.size());
                            }
                        } catch (Exception ex) {
                            statusLabel.setText("ERROR: " + ex.getMessage());
                            ex.printStackTrace();

                            if (logPanel != null) {
                                logPanel.logError(
                                        "Outgoing statements for "
                                                + result.qid(),
                                        ex);
                            }
                        }
                    }
                };

        worker.execute();
    }

    private void showStatementValueDetails(TopQidStatement statement) {
        if (!statement.hasValueQid()) {
            return;
        }

        Window owner =
                SwingUtilities.getWindowAncestor(this);

        TopQidDetailsDialog dialog =
                new TopQidDetailsDialog(
                        owner,
                        client,
                        statement.asQidResult(),
                        logPanel);

        dialog.setVisible(true);
    }

    private void openStatementValue(TopQidStatement statement) {
        if (!statement.hasValueQid()) {
            return;
        }

        openUrl("https://www.wikidata.org/wiki/" + statement.valueQid());
    }

    private void useStatementValue(TopQidStatement statement) {
        if (!statement.hasValueQid()) {
            return;
        }

        statusLabel.setText("Selected value QID: "
                                    + statement.valueDisplay());
    }

    private void openUrl(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
