package wikidata.explore.tree;

import aux.SplitPaneUtils;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.logical.GenerateInstancesQuery;
import wikidata.explore.query.swing.QueryObjectResultPanel;
import wikidata.explore.query.swing.QueryRawLogPanel;
import wikidata.explore.query.workflow.QueryWorkflow;

import javax.swing.*;
import java.awt.*;

/**
 * New conceptual UI:
 *
 * LEFT   = visible class model
 * MIDDLE = selected class/field production source
 * RIGHT  = generated instances
 */
public class ModelBuilderFrame extends JFrame {

    private final WikidataSparqlClient client;
    private final WikidataApiClient apiClient =
            new WikidataApiClient("QuizProject/1.0");

    private final QueryRawLogPanel queryLogPanel =
            new QueryRawLogPanel();

    private QueryWorkflow<GenerationRun> generateWorkflow;

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
        sourceWorkbench.setClient(client);
        sourceWorkbench.setApiClient(apiClient);

        sourceWorkbench.samplePanel().setNodeSupplier(
                sourceWorkbench::temporaryRuleNodeForSelected);

        sourceWorkbench.samplePanel().setFieldSampleSupplier(
                sourceWorkbench::fieldSampleContextForSelected);

        sourceWorkbench.samplePanel().log(this::log);

        sourceWorkbench.discoveryPanel().log(this::log);

        sourceWorkbench.propertyPanel().onPropertySelected(property -> {
            sourceWorkbench.useProperty(property.pid(), property.getName());
            classModelPanel.refresh();
        });

        sourceWorkbench.afterChange(v -> classModelPanel.refresh());

        sourceWorkbench.afterApplyField(f -> {
            classModelPanel.refresh();
            classModelPanel.selectField(f);
            sourceWorkbench.edit(f);
        });
        sourceWorkbench.log(this::log);
        classModelPanel.addTreeSelectionListener(e ->
                sourceWorkbench.edit(classModelPanel.selectedUserObject()));

        QueryContext queryContext =
                new QueryContext(client, apiClient, queryLogPanel);

        generateWorkflow =
                new QueryWorkflow<>(
                        queryContext,
                        run -> SwingUtilities.invokeLater(() -> {
                            if (lastRun != null) {
                                lastRun.runtime().close();
                            }
                            lastRun = run;
                            instancesPanel.accept(run.objectResult());
                        }),
                        queryLogPanel);

        generateButton.addActionListener(e -> {
            // Snapshot the model and widget state on the EDT, before
            // the background run starts.
            GenerateInstancesQuery query =
                    new GenerateInstancesQuery(
                            projectModel.copy(),
                            ((Number) depthSpinner.getValue()).intValue());

            generateButton.setEnabled(false);
            cancelButton.setEnabled(true);

            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    generateWorkflow.run(query);
                    return null;
                }

                @Override
                protected void done() {
                    generateButton.setEnabled(true);
                    cancelButton.setEnabled(false);
                    try {
                        get();
                    } catch (Exception ex) {
                        queryLogPanel.appendRaw(
                                "Generate failed: " + ex.getMessage());
                    }
                }
            }.execute();
        });
        cancelButton.addActionListener(e -> client.cancelCurrentQuery());

        previewButton.addActionListener(e -> previewInternalSparql());
        showGeneratedSourceButton.addActionListener(e -> showGeneratedSource());

        sourceWorkbench.edit(projectModel.rootClass());
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
        SwingUtilities.invokeLater(() -> {
            queryLogPanel.appendRaw(s);
        });
    }

    private static JComponent titled(String title, JComponent component) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(component, BorderLayout.CENTER);
        return p;
    }
}
