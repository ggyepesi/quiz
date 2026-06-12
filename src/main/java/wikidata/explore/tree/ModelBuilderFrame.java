package wikidata.explore.tree;

import aux.SplitPaneUtils;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.generation.GenerationRun;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.logical.GenerateInstancesQuery;
import wikidata.explore.query.swing.QueryObjectResultPanel;
import wikidata.explore.query.swing.QueryRawLogPanel;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.*;
import java.awt.*;

public class ModelBuilderFrame extends JFrame {

    private final WikidataSparqlClient client;

    private final WikidataApiClient apiClient =
            new WikidataApiClient("QuizProject/1.0");

    private final QueryRawLogPanel queryLogPanel =
            new QueryRawLogPanel();

    private final GeneratedProjectModel projectModel =
            GeneratedProjectModel.constellationDemo();

    private GenerationRun lastRun;
    private SwingQueryRunner queryRunner;

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

    private final JSpinner depthSpinner =
            new JSpinner(new SpinnerNumberModel(0, 0, 5, 1));

    public ModelBuilderFrame(WikidataSparqlClient client) {
        super("Wikidata Quizable Model Builder");

        this.client = client;
        client.log(this::log);

        buildUi();
        wireActions();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1750, 950);
        setLocationByPlatform(true);
    }

    private void buildUi() {
        setLayout(new BorderLayout(6, 6));

        JPanel tb = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        tb.add(generateButton);
        tb.add(cancelButton);
        tb.add(previewButton);
        tb.add(showGeneratedSourceButton);
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

        queryLogPanel.setPreferredSize(new Dimension(1200, 160));

        JSplitPane vertical =
                new JSplitPane(
                        JSplitPane.VERTICAL_SPLIT,
                        main,
                        titled("Query log", queryLogPanel));

        vertical.setResizeWeight(0.80);
        vertical.setDividerSize(8);
        vertical.setContinuousLayout(true);

        add(vertical, BorderLayout.CENTER);

        SwingUtilities.invokeLater(() -> {
            main.setDividerLocation(0.60);
            leftMiddle.setDividerLocation(0.2);
            vertical.setDividerLocation(0.80);
        });
    }

    private void wireActions() {
        QueryContext queryContext =
                new QueryContext(client, apiClient, queryLogPanel);

        queryRunner =
                new SwingQueryRunner(queryContext, queryLogPanel);

        queryRunner.registerCancelButton(cancelButton);
        queryRunner.registerRunButton(previewButton);
        queryRunner.registerRunButton(showGeneratedSourceButton);
        queryRunner.cancelAction(client::cancelCurrentQuery);

        sourceWorkbench.setClient(client);
        sourceWorkbench.setQueryRunner(queryRunner);
        sourceWorkbench.log(this::log);

        sourceWorkbench.afterChange(v -> classModelPanel.refresh());

        sourceWorkbench.afterApplyField(f -> {
            classModelPanel.refresh();
            classModelPanel.selectField(f);
            sourceWorkbench.edit(f);
        });

        classModelPanel.addTreeSelectionListener(e ->
                                                         sourceWorkbench.edit(classModelPanel.selectedUserObject()));

        queryRunner.wireButton(
                generateButton,
                this::acceptGenerationRun,
                () -> {
                    sourceWorkbench.applyEdits();

                    return new GenerateInstancesQuery(
                            projectModel.copy(),
                            ((Number) depthSpinner.getValue()).intValue());
                },
                ex -> queryLogPanel.appendRaw(
                        "Generate failed: " + ex.getMessage()));

        previewButton.addActionListener(e -> previewInternalSparql());
        showGeneratedSourceButton.addActionListener(e -> showGeneratedSource());

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
            log("No generated class source yet.\n");
            return;
        }

        JTextArea area =
                new JTextArea(lastRun.runtime().source(), 40, 120);

        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
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

        log("\nPREVIEW internal SPARQL\n-----------------------\n");

        for (String q : extractor.previewQueries(
                root,
                ((Number) depthSpinner.getValue()).intValue())) {
            log(q);
            log("\n");
        }
    }

    private void log(String s) {
        SwingUtilities.invokeLater(() ->
                                           queryLogPanel.appendRaw(s));
    }

    private static JComponent titled(String title, JComponent component) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(component, BorderLayout.CENTER);
        return p;
    }
}