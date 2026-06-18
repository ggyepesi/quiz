package wikidata.explore.workbench;

import aux.SplitPaneUtils;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.RuleTreeExtractor;
import aux.Constants;
import quiz.Quizable;
import wikidata.explore.codegen.GeneratedQuizableRuntime;
import wikidata.explore.codegen.GeneratedQuizableSourceGenerator;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.GenerationRun;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.logical.GenerateInstancesQuery;
import wikidata.explore.query.swing.QueryObjectResultPanel;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.query.swing.WorkflowLogWindow;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.rule.RuleTreeConfig;
import wikidata.explore.rule.RuleTreeSerializer;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

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

    private final JButton showRuleTreeButton =
            new JButton("Show rule tree");

    private final JButton showQueryLogsButton =
            new JButton("Show query logs");

    private final JButton loadSavedButton =
            new JButton("Load saved");

    private final JButton saveEverythingButton =
            new JButton("Save everything");

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
        tb.add(showRuleTreeButton);
        tb.add(showQueryLogsButton);
        tb.add(loadSavedButton);
        tb.add(saveEverythingButton);
        tb.add(new JLabel("Depth:"));
        tb.add(depthSpinner);

        cancelButton.setEnabled(false);

        // The toolbar has grown long; in a narrow window its trailing buttons
        // would be clipped. Keep them reachable via a horizontal scrollbar.
        add(aux.ScrollPaneUtils.horizontalOnly(tb), BorderLayout.NORTH);

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
                this::reportGenerationError);

        loadSavedButton.addActionListener(e -> loadSavedInstances());

        saveEverythingButton.addActionListener(e -> saveEverything());

        previewButton.addActionListener(e ->
                                                previewInternalSparql());

        showGeneratedSourceButton.addActionListener(e ->
                                                            showGeneratedSource());

        showRuleTreeButton.addActionListener(e -> showRuleTree());

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
                saveSnapshot(run);
            } else {
                instancesPanel.clear();
            }
        });
    }

    // Surfaces a generation failure visibly (a dialog plus the log) rather than
    // only writing to a log window the user may not have open, and offers a
    // hint for the failures we recognize.
    private void reportGenerationError(Throwable ex) {
        String message = ex == null ? "Unknown error" : ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.toString();
        }

        String hint = hintFor(message);

        logWindow.info("Generate failed: " + message
                               + (hint.isBlank() ? "" : "\n" + hint));

        String body = message + (hint.isBlank() ? "" : "\n\n" + hint);

        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(
                        this, body, "Generation failed",
                        JOptionPane.ERROR_MESSAGE));
    }

    private static String hintFor(String message) {
        String m = message == null ? "" : message.toLowerCase();

        if (m.contains("can not set") && m.contains("arraylist")) {
            return "Hint: a single-valued field received several values from "
                    + "Wikidata. Set that field's cardinality to Collection to "
                    + "keep them all (otherwise only the first is used).";
        }
        if (m.contains("cannot find symbol")
                || m.contains("compilation")
                || m.contains("compile")) {
            return "Hint: the generated class did not compile. Use "
                    + "'Show generated source' to inspect it; an entity field "
                    + "usually needs a valid object type (or none -> Quizable).";
        }
        if (m.contains("timeout") || m.contains("timed out")) {
            return "Hint: the SPARQL endpoint timed out. Lower the depth/limit, "
                    + "or reduce the number of fields and retry.";
        }
        return "";
    }

    // Loads a previously saved snapshot of dynamic objects and maps them
    // through the current model -- the same materialize path as Generate, but
    // without re-querying Wikidata.
    private void loadSavedInstances() {
        JFileChooser chooser =
                new JFileChooser(new File(Constants.wikidataDataDirectory));
        chooser.setDialogTitle("Load saved instances (snapshot JSON)");

        File suggested = snapshotFile();
        if (suggested.isFile()) {
            chooser.setSelectedFile(suggested);
        }

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File file = chooser.getSelectedFile();

        try {
            sourceWorkbench.applyEdits();

            GeneratedProjectModel snapshot = projectModel.copy();
            List<WikidataDynamicObject> objects =
                    new WikidataDynamicObjectJsonStore().load(file);

            GenerationPipeline pipeline = new GenerationPipeline();
            RuleNode plan = pipeline.plan(snapshot);
            GeneratedQuizableRuntime runtime = pipeline.buildRuntime(snapshot);
            List<Quizable> instances =
                    pipeline.materialize(runtime, objects);

            acceptGenerationRun(new GenerationRun(
                    snapshot, 0, plan, objects, runtime, instances));

            logWindow.info("Loaded " + objects.size()
                                   + " saved object(s) from " + file.getName()
                                   + "; mapped " + instances.size()
                                   + " instance(s).");
        } catch (Exception ex) {
            reportGenerationError(ex);
        }
    }

    // Persists a successful run's downloaded objects so they can be reloaded
    // later without re-querying. Best-effort: a failure here never breaks the
    // run that just succeeded.
    private void saveSnapshot(GenerationRun run) {
        if (run == null || run.dynamicObjects() == null
                || run.dynamicObjects().isEmpty()) {
            return;
        }
        try {
            File file = snapshotFile();
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            new WikidataDynamicObjectJsonStore()
                    .save(run.dynamicObjects(), file);
            logWindow.info("Saved snapshot (" + run.dynamicObjects().size()
                                   + " objects) to " + file.getPath());
        } catch (Exception ex) {
            logWindow.info("Could not save snapshot: " + ex.getMessage());
        }
    }

    // The project's data directory, keyed off its name so it lands where the
    // web client looks: "Constellations" -> data/wikidata/constellations/,
    // i.e. the exact folder the web GeneratedSource and "Load saved" read.
    private String projectKey() {
        String name = projectModel.name();
        if (name == null || name.isBlank()) {
            return "generated";
        }
        String key = name.toLowerCase().replaceAll("[^a-z0-9]+", "");
        return key.isBlank() ? "generated" : key;
    }

    private File projectDataDir() {
        return new File(Constants.wikidataDataDirectory + projectKey() + "/");
    }

    private File snapshotFile() {
        return new File(projectDataDir(), projectKey() + ".snapshot.json");
    }

    private File ruleTreeFile() {
        return new File(projectDataDir(), projectKey() + ".ruletree.json");
    }

    private File modelFile() {
        return new File(projectDataDir(), projectKey() + ".model.json");
    }

    // Saves config (the editable project model), the compiled rule tree, and
    // the generated instances together into the project's data directory — the
    // instances file being exactly what the web client and "Load saved" read.
    private void saveEverything() {
        sourceWorkbench.applyEdits();

        StringBuilder report = new StringBuilder();
        try {
            modelFile().getParentFile().mkdirs();

            new GeneratedProjectModelStore().save(projectModel, modelFile());
            report.append("Config:    ").append(modelFile().getPath()).append('\n');

            RuleNode root = RuleTreeCompiler.compileProject(projectModel);
            new RuleTreeSerializer().save(root, ruleTreeFile());
            report.append("Rule tree: ").append(ruleTreeFile().getPath()).append('\n');

            int n = -1;
            if (lastRun != null && lastRun.dynamicObjects() != null
                    && !lastRun.dynamicObjects().isEmpty()) {
                new WikidataDynamicObjectJsonStore()
                        .save(lastRun.dynamicObjects(), snapshotFile());
                n = lastRun.dynamicObjects().size();
                report.append("Instances: ").append(n)
                      .append(" -> ").append(snapshotFile().getPath()).append('\n');
            } else {
                report.append("Instances: (none generated yet — run "
                                       + "\"Generate instances\" first)\n");
            }

            logWindow.info("Saved everything:\n" + report);

            String hint = instanceCountHint(n);
            JOptionPane.showMessageDialog(
                    this,
                    report + (hint.isBlank() ? "" : "\n" + hint),
                    "Saved everything",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            reportGenerationError(ex);
        }
    }

    // The Constellation membership (P31 = Q8928) yields ~92 hits, four more
    // than the 88 modern IAU constellations: the extras lack an IAU
    // abbreviation (P1813). Point the user at the filter that drops them.
    private String instanceCountHint(int n) {
        if (n == 92) {
            return "Hint: 92 includes 4 non-standard constellations. Tick "
                    + "\"Required\" on the IAU abbreviation (P1813) field and "
                    + "regenerate to get the canonical 88.";
        }
        return "";
    }

    private void showGeneratedSource() {
        // Prefer the source of the last successfully compiled run; otherwise
        // generate it straight from the current model so the feature works for
        // a saved/edited class that hasn't been run (or one whose last run
        // failed to compile -- seeing the source is exactly how you debug that).
        sourceWorkbench.applyEdits();

        String source;
        String title;

        if (lastRun != null && lastRun.runtime() != null) {
            source = lastRun.runtime().source();
            title = lastRun.runtime().qualifiedClassName();
        } else {
            GeneratedQuizableSourceGenerator gen =
                    new GeneratedQuizableSourceGenerator(
                            GeneratedQuizableSourceGenerator.GENERATED_PACKAGE);
            source = gen.sourceFor(projectModel.rootClass());
            title = gen.qualifiedClassName(projectModel.rootClass())
                    + "  (preview - not yet compiled)";
        }

        JTextArea area = new JTextArea(source, 40, 120);

        area.setFont(
                new Font(
                        Font.MONOSPACED,
                        Font.PLAIN,
                        12));

        area.setCaretPosition(0);

        JOptionPane.showMessageDialog(
                this,
                new JScrollPane(area),
                title,
                JOptionPane.PLAIN_MESSAGE);
    }

    // Shows the compiled rule tree (the plan the generator walks to extract
    // and map instances), serialized from the current model — so you can see
    // the node/edge/included-field structure without running a generation.
    private void showRuleTree() {
        sourceWorkbench.applyEdits();

        String text;
        try {
            RuleNode root = RuleTreeCompiler.compileProject(projectModel);
            text = new RuleTreeSerializer().mapper()
                    .writeValueAsString(RuleTreeConfig.of(root));
        } catch (Exception ex) {
            reportGenerationError(ex);
            return;
        }

        JTextArea area = new JTextArea(text, 40, 100);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setEditable(false);
        area.setCaretPosition(0);

        JOptionPane.showMessageDialog(
                this,
                new JScrollPane(area),
                "Rule tree — " + projectModel.rootClass().className(),
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