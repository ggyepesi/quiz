package wikidata.explore.tree;

import aux.SplitPaneUtils;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.result.ObjectQueryResult;
import wikidata.explore.query.swing.QueryRawLogPanel;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

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

    private final GeneratedProjectModel projectModel =
            GeneratedProjectModel.constellationDemo();

    private GeneratedQuizableRuntime lastRuntime;

    private final SingleRootClassModelPanel classModelPanel =
            new SingleRootClassModelPanel(projectModel);

    private final ModelSourceWorkbenchPanel sourceWorkbench =
            new ModelSourceWorkbenchPanel(projectModel);

    private final GeneratedInstancesPanel instancesPanel =
            new GeneratedInstancesPanel();

    private final QueryRawLogPanel queryLogPanel =
            new QueryRawLogPanel();

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

    private record GenerationResult(
            List<WikidataDynamicObject> objects,
            GeneratedQuizableRuntime runtime) {}

    private SwingWorker<GenerationResult, String> currentWorker;

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
                        0.28);

        JSplitPane main =
                SplitPaneUtils.horizontal(
                        leftMiddle,
                        titled("Generated instances", instancesPanel),
                        0.66);


        queryLogPanel.setPreferredSize(new Dimension(1200, 160));

        titled("Query log", queryLogPanel);
        JSplitPane vertical =
                new JSplitPane(
                        JSplitPane.VERTICAL_SPLIT,
                        main,
                        titled("Log / SPARQL", queryLogPanel));

        vertical.setResizeWeight(0.80);
        vertical.setDividerSize(8);
        vertical.setContinuousLayout(true);

        add(vertical, BorderLayout.CENTER);
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

        generateButton.addActionListener(e -> generateInstances());
        cancelButton.addActionListener(e -> cancelGeneration());
        previewButton.addActionListener(e -> previewInternalSparql());
        showGeneratedSourceButton.addActionListener(e -> showGeneratedSource());

        client.registerRunButton(generateButton);
        client.registerCancelButton(cancelButton);

        sourceWorkbench.edit(projectModel.rootClass());
    }

    private void showGeneratedSource() {
        if (lastRuntime == null) {
            log("No generated class source yet.\n");
            return;
        }

        JTextArea area =
                new JTextArea(lastRuntime.source(), 40, 120);

        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);

        JOptionPane.showMessageDialog(
                this,
                new JScrollPane(area),
                lastRuntime.qualifiedClassName(),
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

    private void generateInstances() {
        if (currentWorker != null && !currentWorker.isDone()) {
            log("Generation is already running.\n");
            return;
        }

        RuleNode root =
                RuleTreeCompiler.compileProject(projectModel);

        int depth =
                ((Number) depthSpinner.getValue()).intValue();

        RuleTreeExtractor extractor =
                new RuleTreeExtractor(client);

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        currentWorker =
                new SwingWorker<>() {
                    @Override
                    protected GenerationResult doInBackground()
                            throws Exception {
                        List<WikidataDynamicObject> objects =
                                extractor.load(root, depth, this::publish);
                        GeneratedQuizableRuntime runtime =
                                new GeneratedQuizableRuntimeBuilder()
                                        .build(projectModel.rootClass());
                        return new GenerationResult(objects, runtime);
                    }

                    @Override
                    protected void process(List<String> chunks) {
                        for (String s : chunks) {
                            log(s);
                        }
                    }

                    @Override
                    protected void done() {
                        log("ModelBuilderFrame worker done...\n");

                        if (isCancelled()) {
                            log("Generation cancelled.\n");
                            return;
                        }

                        try {
                            GenerationResult result = get();

                            log("Root/dynamic objects returned: "
                                        + result.objects().size()
                                        + "\n");

                            lastRuntime = result.runtime();

                            List<quiz.Quizable> generatedObjects =
                                    new GeneratedQuizableMapper(lastRuntime)
                                            .mapRoots(result.objects());

                            instancesPanel.accept(
                                    new ObjectQueryResult(
                                    generatedObjects,
                                    lastRuntime.generatedClass(),
                                    lastRuntime.source()));

                            log("After setGeneratedObjects\n");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log("Generation interrupted.\n");

                        } catch (CancellationException e) {
                            log("Generation cancelled.\n");

                        } catch (ExecutionException e) {
                            Throwable cause = e.getCause();

                            log("Generation failed: "
                                        + cause.getClass().getSimpleName()
                                        + ": "
                                        + cause.getMessage()
                                        + "\n");

                            cause.printStackTrace();
                        } catch (Exception e) {
                            log("Generation failed: "
                                        + e.getClass().getSimpleName()
                                        + ": "
                                        + e.getMessage()
                                        + "\n");
                        } finally {
                            currentWorker = null;
                            setCursor(Cursor.getDefaultCursor());
                        }
                    }
                };
        currentWorker.execute();
    }

    private void cancelGeneration() {
        if (currentWorker != null && !currentWorker.isDone()) {
            log("Cancelling generation...\n");
            currentWorker.cancel(true);
            client.cancelCurrentQuery();
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
