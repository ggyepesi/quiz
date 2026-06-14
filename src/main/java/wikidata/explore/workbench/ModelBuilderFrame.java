package wikidata.explore.workbench;

import aux.SplitPaneUtils;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.RuleTreeExtractor;
import wikidata.explore.generation.GenerationRun;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.logical.GenerateInstancesQuery;
import wikidata.explore.query.swing.QueryObjectResultPanel;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.query.swing.WorkflowLogWindow;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.rule.RuleTreeCompiler;

import javax.swing.*;
import java.awt.*;

public class ModelBuilderFrame extends JFrame {

    private final WikidataSparqlClient client;

    private final WikidataApiClient apiClient =
            new WikidataApiClient("QuizProject/1.0");

    private final GeneratedProjectModel projectModel =
            GeneratedProjectModel.constellationDemo();

    private GenerationRun lastRun;

    private final SingleRootClassModelPanel classModelPanel =
            new SingleRootClassModelPanel(projectModel);

    private final ModelSourceWorkbenchPanel sourceWorkbench =
            new ModelSourceWorkbenchPanel(projectModel);

    private final QueryObjectResultPanel instancesPanel =
            new QueryObjectResultPanel();

    private final JButton generateButton =
            new JButton("Generate instances");

    private final JButton cancelButton =
            new JButton("Cancel");

    private final JButton previewButton =
            new JButton("Preview internal SPARQL");

    private final JButton showGeneratedSourceButton =
            new JButton("Show generated source");

    private final JButton showQueryLogsButton =
            new JButton("Show query logs");

    private final JSpinner depthSpinner =
            new JSpinner(new SpinnerNumberModel(0, 0, 5, 1));

    private final WorkflowLogWindow logWindow =
            new WorkflowLogWindow();

    public ModelBuilderFrame(WikidataSparqlClient client) {
        super("Wikidata Quizable Model Builder");

        this.client = client;

        buildUi();
        wireActions();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1750, 950);
        setLocationByPlatform(true);
    }

    private void buildUi() {
        setLayout(new BorderLayout(6, 6));

        JPanel tb =
                new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        tb.add(generateButton);
        tb.add(cancelButton);
        tb.add(previewButton);
        tb.add(showGeneratedSourceButton);
        tb.add(showQueryLogsButton);
        tb.add(new JLabel("Depth:"));
        tb.add(depthSpinner);

        cancelButton.setEnabled(false);

        add(tb, BorderLayout.NORTH);

        JSplitPane leftMiddle =
                SplitPaneUtils.horizontal(
                        titled("Class model", classModelPanel),
                        titled("Production source", sourceWorkbench),
                        0.2);

        JSplitPane main =
                SplitPaneUtils.horizontal(
                        leftMiddle,
                        titled("Generated instances", instancesPanel),
                        0.6);

        leftMiddle.setResizeWeight(0.2);
        main.setResizeWeight(0.60);

        add(main, BorderLayout.CENTER);

        SwingUtilities.invokeLater(() -> {
            main.setDividerLocation(0.60);
            leftMiddle.setDividerLocation(0.2);
        });
    }

    private void wireActions() {
        QueryContext queryContext =
                new QueryContext(client, apiClient);

        SwingQueryRunner queryRunner =
                new SwingQueryRunner(
                        queryContext,
                        logWindow);

        queryRunner.registerCancelButton(cancelButton);
        queryRunner.registerRunButton(previewButton);
        queryRunner.registerRunButton(showGeneratedSourceButton);
        queryRunner.cancelAction(client::cancelCurrentQuery);

        sourceWorkbench.setQueryRunner(queryRunner);

        sourceWorkbench.afterChange(v ->
                                            classModelPanel.refresh());

        sourceWorkbench.afterApplyField(f -> {
            classModelPanel.refresh();
            classModelPanel.selectField(f);
            sourceWorkbench.edit(f);
        });

        classModelPanel.addTreeSelectionListener(e ->
                                                         sourceWorkbench.edit(
                                                                 classModelPanel.selectedUserObject()));

        queryRunner.wireButton(
                generateButton,
                this::acceptGenerationRun,
                () -> {
                    sourceWorkbench.applyEdits();

                    return new GenerateInstancesQuery(
                            projectModel.copy(),
                            ((Number) depthSpinner.getValue()).intValue());
                },
                ex -> logWindow.info(
                        "Generate failed: "
                                + ex.getMessage()));

        previewButton.addActionListener(e ->
                                                previewInternalSparql());

        showGeneratedSourceButton.addActionListener(e ->
                                                            showGeneratedSource());

        showQueryLogsButton.addActionListener(e ->
                                                      logWindow.show(this));

        sourceWorkbench.edit(projectModel.rootClass());
    }

    private void acceptGenerationRun(GenerationRun run) {
        SwingUtilities.invokeLater(() -> {
            if (lastRun != null && lastRun.runtime() != null) {
                lastRun.runtime().close();
            }

            lastRun = run;

            if (run != null) {
                instancesPanel.accept(run.objectResult());
            } else {
                instancesPanel.clear();
            }
        });
    }

    private void showGeneratedSource() {
        if (lastRun == null) {
            logWindow.info("No generated class source yet.");
            return;
        }

        JTextArea area =
                new JTextArea(
                        lastRun.runtime().source(),
                        40,
                        120);

        area.setFont(
                new Font(
                        Font.MONOSPACED,
                        Font.PLAIN,
                        12));

        area.setCaretPosition(0);

        JOptionPane.showMessageDialog(
                this,
                new JScrollPane(area),
                lastRun.runtime().qualifiedClassName(),
                JOptionPane.PLAIN_MESSAGE);
    }

    private void previewInternalSparql() {
        sourceWorkbench.applyEdits();

        RuleNode root =
                RuleTreeCompiler.compileProject(projectModel);

        RuleTreeExtractor extractor =
                new RuleTreeExtractor(client);

        StringBuilder sb =
                new StringBuilder();

        sb.append("PREVIEW internal SPARQL\n")
          .append("-----------------------\n\n");

        for (String q : extractor.previewQueries(
                root,
                ((Number) depthSpinner.getValue()).intValue())) {
            sb.append(q).append("\n\n");
        }

        logWindow.info(sb.toString());
        logWindow.show(this);
    }

    private static JComponent titled(
            String title,
            JComponent component) {

        JPanel p =
                new JPanel(new BorderLayout());

        p.setBorder(
                BorderFactory.createTitledBorder(title));

        p.add(component, BorderLayout.CENTER);

        return p;
    }
}