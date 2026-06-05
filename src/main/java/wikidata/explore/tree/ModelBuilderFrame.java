package wikidata.explore.tree;

import aux.SplitPaneUtils;
import wikidata.WikidataSparqlClient;
import wikidata.explore.model.GeneratedProjectModel;

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

    private final GeneratedProjectModel projectModel =
            GeneratedProjectModel.constellationDemo();

    private GeneratedQuizableRuntime lastRuntime;

    private final SingleRootClassModelPanel classModelPanel =
            new SingleRootClassModelPanel(projectModel);

    private final ModelSourceWorkbenchPanel sourceWorkbench =
            new ModelSourceWorkbenchPanel(projectModel);

    private final GeneratedInstancesPanel instancesPanel =
            new GeneratedInstancesPanel();

    private final JTextArea logArea =
            new JTextArea(8, 100);

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

    private SwingWorker<List<WikidataDynamicObject>, String> currentWorker;

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

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(1200, 160));

        JSplitPane vertical =
                new JSplitPane(
                        JSplitPane.VERTICAL_SPLIT,
                        main,
                        titled("Log / SPARQL", logScroll));

        vertical.setResizeWeight(0.80);
        vertical.setDividerSize(8);
        vertical.setContinuousLayout(true);

        add(vertical, BorderLayout.CENTER);
    }

    private void wireActions() {
        sourceWorkbench.setClient(client);

        sourceWorkbench.samplePanel().setNodeSupplier(
                sourceWorkbench::temporaryRuleNodeForSelected);

        sourceWorkbench.samplePanel().setFieldSampleSupplier(
                sourceWorkbench::fieldSampleContextForSelected);

        sourceWorkbench.discoveryPanel().setNodeSupplier(
                sourceWorkbench::temporaryRuleNodeForSelected);

        sourceWorkbench.propertyPanel().onPropertySelected(property -> {
            sourceWorkbench.useProperty(property.pid(), property.getName());
            classModelPanel.refresh();
        });

        sourceWorkbench.afterChange(v -> classModelPanel.refresh());

        classModelPanel.addTreeSelectionListener(e ->
                sourceWorkbench.edit(classModelPanel.selectedUserObject()));

        generateButton.addActionListener(e -> generateInstances());
        cancelButton.addActionListener(e -> cancelGeneration());
        previewButton.addActionListener(e -> previewInternalSparql());
        showGeneratedSourceButton.addActionListener(e -> showGeneratedSource());

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

        generateButton.setEnabled(false);
        cancelButton.setEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        currentWorker =
                new SwingWorker<>() {
                    @Override
                    protected List<WikidataDynamicObject> doInBackground()
                            throws Exception {
                        return extractor.load(root, depth, this::publish);
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
                            List<WikidataDynamicObject> objects = get();

                            log("Root/dynamic objects returned: "
                                        + objects.size()
                                        + "\n");

                            log("Before runtime build\n");

                            lastRuntime =
                                    new GeneratedQuizableRuntimeBuilder()
                                            .build(projectModel.rootClass());

                            log("Before mapRoots\n");

                            List<quiz.Quizable> generatedObjects =
                                    new GeneratedQuizableMapper(lastRuntime)
                                            .mapRoots(objects);

                            log("Before setGeneratedObjects\n");

                            instancesPanel.setGeneratedObjects(
                                    generatedObjects,
                                    lastRuntime.generatedClass());

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
                            generateButton.setEnabled(true);
                            cancelButton.setEnabled(false);
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
            logArea.append(s);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private static JComponent titled(String title, JComponent component) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(component, BorderLayout.CENTER);
        return p;
    }
}
