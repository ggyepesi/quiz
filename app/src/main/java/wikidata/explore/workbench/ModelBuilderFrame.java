package wikidata.explore.workbench;

import aux.SplitPaneUtils;
import objectview.render.CardListView;
import objectview.render.RenderContext;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import aux.Constants;
import objectview.Viewable;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.codegen.GeneratedViewableSourceGenerator;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.GenerationRun;
import wikidata.explore.generation.GenerateDomainProcess;
import process.ProcessStatus;
import process.swing.SwingProcessInputHandler;
import process.swing.SwingProcessRunner;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.model.Selection;
import wikidata.explore.model.VocabularySelection;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.logical.GenerateInstancesQuery;
import wikidata.explore.query.logical.RemapInstancesQuery;
import wikidata.explore.query.swing.QueryObjectResultPanel;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.query.swing.SwingQuerySession;
import wikidata.explore.query.swing.WorkflowLogWindow;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.rule.RuleTreeConfig;
import wikidata.explore.rule.RuleTreeExplainer;
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

    // Generated instances live in their own window (like the query logs), so
    // the main window is all about configuration.
    private JFrame instancesWindow;

    // Name collisions from the last run (names mapping to >1 entity), shown on
    // demand in their own panel from the instances window.
    private java.util.List<NameCollision> lastCollisions = java.util.List.of();

    private final JButton nameCollisionsButton =
            new JButton("Name collisions");

    private final JButton generateButton =
            new JButton("Generate instances");

    // Generates EVERY class in the domain into one snapshot (each served as its
    // own type), vs "Generate instances" which does the selected class only.
    private final JButton generateDomainButton =
            new JButton("Generate domain");

    // Re-materialize the LAST download with the current model (no re-fetch) — picks
    // up canonicalization / display-name / mapping edits fast.
    private final JButton remapButton =
            new JButton("Remap (no download)");

    private final JButton cancelButton =
            new JButton("Cancel");

    private final JButton showInstancesButton =
            new JButton("Show instances");

    private final JButton showStatementsButton =
            new JButton("Statements…");

    // Example-first statement view (#91): sample the selected class + show its
    // instances' statements-with-qualifiers, in its own window.
    private JFrame statementsWindow;
    private StatementSummaryPanel statementsPanel;

    private final JButton showGeneratedSourceButton =
            new JButton("Show generated source");

    private final JButton showRuleTreeButton =
            new JButton("Show rule tree");

    private final JButton showQueryLogsButton =
            new JButton("Show query logs");

    private final JButton showExplorerButton =
            new JButton("Explorer tools");

    private final JButton showGraphButton =
            new JButton("Model graph");

    private final JButton showSelectionsButton =
            new JButton("Selections");

    private final JButton showGuideButton =
            new JButton("Guide…");

    private JFrame guideWindow;
    private ModelingGuidePanel guidePanel;

    private JFrame selectionsWindow;
    private SelectionViewerPanel selectionsPanel;

    // Companion window holding the discovery tools (Explore/Sample/Discover/
    // WikiProject/Properties), like the instances + logs windows.
    private JFrame explorerWindow;

    // Graph view of the model (classes = nodes, reference fields = edges).
    private JFrame graphWindow;
    private final RuleTreeGraphPanel graphPanel = new RuleTreeGraphPanel();

    // Domain = the whole project (name + its classes), keyed by name. The combo
    // lists every domain in the dataset registry; New/Rename/Delete manage them.
    private final JComboBox<String> domainBox = new JComboBox<>();
    private final JButton newDomainButton = new JButton("New domain");
    private final JButton renameDomainButton = new JButton("Rename domain");
    private final JButton deleteDomainButton = new JButton("Delete domain");
    // Guards the combo's listener while we repopulate/select it programmatically.
    private boolean updatingDomainBox = false;

    private final JButton loadModelButton =
            new JButton("Load model");

    private final JButton loadSavedButton =
            new JButton("Load saved");

    private final JButton saveEverythingButton =
            new JButton("Save domain");

    // Default 1, not 0: depth 0 silently drops every child-object edge (e.g. a
    // constellation's stars), which is a recurring "why are there no stars?"
    // trap. One level of children is the common intent; deeper is opt-in.
    private final JSpinner depthSpinner =
            new JSpinner(new SpinnerNumberModel(1, 0, 5, 1));
    // True while syncing the spinner to the selected class (so the change
    // listener doesn't write the value straight back).
    private boolean syncingDepth = false;

    private final SwingQuerySession querySession;
    private final WorkflowLogWindow logWindow;
    private final SwingProcessRunner processRunner;

    public ModelBuilderFrame(WikidataSparqlClient client) {
        super("Wikidata Viewable Model Builder");

        this.client = client;
        QueryContext queryContext = new QueryContext(client, apiClient);
        this.querySession = new SwingQuerySession(queryContext);
        this.logWindow = querySession.logs();
        this.processRunner = new SwingProcessRunner(
                queryContext, logWindow, new SwingProcessInputHandler(this));

        // Continue from the saved model (so edits like sharesBorderWith / the
        // Star class persist across restarts) instead of the hard-coded demo.
        loadSavedModelIfPresent();

        buildUi();
        wireActions();
        refreshDomainBox();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1750, 950);
        setLocationByPlatform(true);
    }

    private void buildUi() {
        setLayout(new BorderLayout(6, 6));

        // Buttons now live with the panel they act on:
        //  LEFT ("Domain & Classes") — domain CRUD + save/load, the class tree
        //  (with its own class/field actions), and the run controls.
        //  RIGHT ("Configuration") — the artifact viewers + the config editor.
        cancelButton.setEnabled(false);

        JSplitPane main =
                SplitPaneUtils.horizontal(
                        titled("Domain & Classes", buildDomainClassPanel()),
                        titled("Configuration", buildConfigPanel()),
                        0.34);

        main.setResizeWeight(0.34);

        add(main, BorderLayout.CENTER);

        SwingUtilities.invokeLater(() -> main.setDividerLocation(0.34));
    }

    // LEFT panel: the selected domain, its classes, and the run controls.
    private JPanel buildDomainClassPanel() {
        // Domain section (top): selector + domain-level actions.
        domainBox.setToolTipText("The domain (project) being edited. Every class "
                + "below belongs to it. Switch to load another saved domain.");
        saveEverythingButton.setToolTipText("Save this domain — its model, rule "
                + "tree and generated instances — together.");

        JPanel domainPick = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        domainPick.add(new JLabel("Domain:"));
        domainPick.add(domainBox);
        domainPick.add(newDomainButton);
        domainPick.add(renameDomainButton);
        domainPick.add(deleteDomainButton);

        JPanel domainFiles = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        domainFiles.add(saveEverythingButton);
        domainFiles.add(loadModelButton);
        domainFiles.add(loadSavedButton);

        JPanel domainSection = new JPanel(new GridLayout(0, 1, 0, 0));
        domainSection.add(domainPick);
        domainSection.add(domainFiles);

        // Run section (bottom): generate + see the results/logs + depth.
        JLabel depthLabel = new JLabel("Depth:");
        String depthTip = "<html>How many levels of <b>child-object</b> reference "
                + "edges to follow when generating.<br>"
                + "0 = just the root class's instances (with scalar and inlined "
                + "list fields).<br>"
                + "1+ = also fetch the referenced objects' own fields, "
                + "recursively, that many levels deep.</html>";
        depthLabel.setToolTipText(depthTip);
        depthSpinner.setToolTipText(depthTip);

        // Two rows so nothing overflows (and "Depth" stays visible) in this
        // narrower panel: generate/depth on top, the result viewers below.
        generateButton.setToolTipText("Generate the SELECTED class only.");
        generateDomainButton.setToolTipText("Generate EVERY class in the domain "
                + "into one snapshot — each served as its own quiz type.");
        JPanel runRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        runRow1.add(generateButton);
        runRow1.add(generateDomainButton);
        remapButton.setToolTipText("Re-materialize the last download with the "
                + "current model — applies display-name / canonicalization / mapping "
                + "edits without re-fetching from Wikidata.");
        runRow1.add(remapButton);
        runRow1.add(cancelButton);
        runRow1.add(depthLabel);
        runRow1.add(depthSpinner);

        JPanel runRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        runRow2.add(showInstancesButton);
        showStatementsButton.setToolTipText("Sample the selected class and show its "
                + "instances' statements — property → values with qualifiers nested, "
                + "plus coverage badges (example-first field discovery, #91).");
        runRow2.add(showStatementsButton);
        runRow2.add(showQueryLogsButton);

        JPanel runSection = new JPanel(new GridLayout(0, 1, 0, 0));
        runSection.add(runRow1);
        runSection.add(runRow2);

        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(domainSection, BorderLayout.NORTH);
        panel.add(classModelPanel, BorderLayout.CENTER);
        panel.add(runSection, BorderLayout.SOUTH);
        return panel;
    }

    // RIGHT panel: the config editor, with the artifact viewers + Explorer in
    // a header.
    private JPanel buildConfigPanel() {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        header.add(showGeneratedSourceButton);
        header.add(showRuleTreeButton);
        header.add(showExplorerButton);
        header.add(showGraphButton);
        showSelectionsButton.setToolTipText("Browse the domain's Selections — named "
                + "vocabularies/populations referenced but never served; declare a "
                + "vocabulary and inspect its members");
        header.add(showSelectionsButton);
        showGuideButton.setToolTipText("Guided build steps for the selected class: "
                + "what's done, what's next, the tool for it, and the hint");
        header.add(showGuideButton);

        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(header, BorderLayout.NORTH);
        panel.add(sourceWorkbench, BorderLayout.CENTER);
        return panel;
    }

    // Lazily-created window hosting the generated-instances result panel.
    private void showInstancesWindow() {
        if (instancesWindow == null) {
            instancesWindow = new JFrame("Generated instances");
            instancesWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            instancesWindow.setLayout(new BorderLayout());

            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            nameCollisionsButton.setToolTipText(
                    "Names shared by several distinct entities (ambiguous quiz "
                            + "answers) — shown as name + List<Source>");
            nameCollisionsButton.addActionListener(e -> showNameCollisions());
            updateNameCollisionsButton();
            toolbar.add(nameCollisionsButton);

            instancesWindow.add(toolbar, BorderLayout.NORTH);
            instancesWindow.add(instancesPanel, BorderLayout.CENTER);
            instancesWindow.setSize(1100, 850);
            instancesWindow.setLocationByPlatform(true);
        }
        // Stamp the generated class (+ count) on the title — a single-class run
        // renders as a plain search view with no type label, so without this
        // there's no on-screen indication of which class you're looking at.
        instancesWindow.setTitle(instancesTitle());
        instancesWindow.setVisible(true);
        instancesWindow.toFront();
    }

    // Lazily-created window hosting the discovery tools moved out of the main
    // frame, so the main window stays focused on domain + configuration.
    // Sample the selected class, then open the statement view on those instances.
    private void showStatementsWindow(wikidata.explore.query.swing.SwingQueryRunner queryRunner) {
        wikidata.explore.rule.RuleNode node = sourceWorkbench.temporaryRuleNodeForSelected();
        if (node == null) {
            logWindow.info("Statements: select a class first.\n");
            return;
        }
        queryRunner.runQuiet(
                new wikidata.explore.query.logical.SampleClassQuery(node, 5),
                result -> {
                    java.util.List<String> qids = new java.util.ArrayList<>();
                    for (java.util.List<Object> row : result.rows()) {
                        if (!row.isEmpty() && row.get(0) != null
                                && row.get(0).toString().matches("Q\\d+")) {
                            qids.add(row.get(0).toString());
                        }
                    }
                    SwingUtilities.invokeLater(() -> openStatementsWindow(qids));
                },
                ex -> logWindow.info("Statements sample failed: " + ex.getMessage() + "\n"));
    }

    private void openStatementsWindow(java.util.List<String> qids) {
        if (statementsWindow == null) {
            statementsPanel = new StatementSummaryPanel(client);
            // Clicking + on a statement/qualifier flows through the same Add-Field
            // path as Discover: a property → a field, a qualifier → a qualifier
            // field, both on the selected class, opened pre-filled in the editor.
            statementsPanel.onConfigureField((pid, label) ->
                    sourceWorkbench.useProperty(pid, label,
                            wikidata.explore.model.RuleDirection.ROOT_TO_ITEM));
            statementsPanel.onConfigureQualifier((pid, label) ->
                    sourceWorkbench.useQualifier(pid, label));
            statementsWindow = new JFrame("Statements — example-first (#91)");
            statementsWindow.add(statementsPanel);
            statementsWindow.setSize(780, 720);
            statementsWindow.setLocationRelativeTo(this);
        }
        statementsWindow.setVisible(true);
        statementsWindow.toFront();
        if (!qids.isEmpty()) {
            statementsPanel.showFor(qids);
        }
    }

    private void showExplorerWindow() {
        if (explorerWindow == null) {
            explorerWindow = new JFrame("Explorer tools");
            explorerWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            explorerWindow.setLayout(new BorderLayout());
            explorerWindow.add(sourceWorkbench.helperTools(), BorderLayout.CENTER);
            explorerWindow.setSize(900, 850);
            explorerWindow.setLocationByPlatform(true);
        }
        explorerWindow.setVisible(true);
        explorerWindow.toFront();
    }

    // Model-as-graph view: classes are nodes, entity-reference fields are edges.
    // Clicking a node selects that class in the workbench (the graph is a map +
    // selector, not an editor — the class/field panels stay where they are).
    // Browse the domain's Selections — named selections over the entity pool
    // (vocabularies / populations) that are referenced but never served. A
    // Selection's content is inspectable even though it isn't a product; declare a
    // vocabulary and see its members.
    private void showSelectionsWindow() {
        if (selectionsWindow == null) {
            selectionsPanel = new SelectionViewerPanel(projectModel, apiClient, client);
            selectionsWindow = new JFrame("Selections");
            selectionsWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            selectionsWindow.setLayout(new BorderLayout());
            selectionsWindow.add(selectionsPanel, BorderLayout.CENTER);
            selectionsWindow.setSize(560, 680);
            selectionsWindow.setLocationByPlatform(true);
        }
        selectionsPanel.refreshSelections();
        selectionsWindow.setVisible(true);
        selectionsWindow.toFront();
    }

    private void showGraphWindow() {
        if (graphWindow == null) {
            graphWindow = new JFrame("Model graph");
            graphWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            graphWindow.setLayout(new BorderLayout());
            graphWindow.add(new JScrollPane(graphPanel), BorderLayout.CENTER);
            graphWindow.setSize(720, 600);
            graphWindow.setLocationByPlatform(true);
            graphPanel.onClassSelected(name -> {
                GeneratedClassModel c = classByName(name);
                if (c != null) {
                    classModelPanel.selectClass(c); // fires the tree listener
                }
            });
            // Double-click a node → jump into live Explore of that class's
            // membership target (its Wikidata type/award), unifying the model
            // graph with the navigation canvas.
            graphPanel.onClassExplore(name -> {
                GeneratedClassModel c = classByName(name);
                if (c == null) {
                    return;
                }
                String qid = c.effectiveInstanceMapping(projectModel).sourceQid();
                if (qid == null || !qid.matches("Q\\d+")) {
                    logWindow.info("Class \"" + name + "\" has no membership target "
                            + "QID to explore — set a Relation target first.");
                    return;
                }
                showExplorerWindow();
                sourceWorkbench.explorePanel().exploreQid(qid, name);
            });
        }
        graphPanel.setModel(projectModel);
        graphWindow.setVisible(true);
        graphWindow.toFront();
    }

    private String instancesTitle() {
        if (lastRun == null) {
            return "Generated instances";
        }
        // Per-class counts, biggest first — the total alone was misleading (it
        // reads as if every instance is the root class, when it's the whole pool
        // across classes, e.g. films + nominations + categories).
        // Count DISTINCT entities per class, by QID — the served/saved artifact is
        // keyed by QID (one instance per QID), so counting raw pool objects
        // over-reports: a QID that is both a class root and a bare reference child, or
        // a blank-QID ref, is one saved entity but several pool objects. Skipping
        // non-QID objects + deduping by QID makes this preview match the reload.
        java.util.Map<String, java.util.Set<String>> qidsByType =
                new java.util.LinkedHashMap<>();
        if (lastRun.dynamicObjects() != null) {
            for (WikidataDynamicObject o : lastRun.dynamicObjects()) {
                if (o == null || o.typeName() == null || o.typeName().isBlank()
                        // untyped sentinel: demoted duplicate statements / bare
                        // reference children, not first-class class instances.
                        || "WikidataDynamicObject".equals(o.typeName())) {
                    continue;
                }
                String qid = o.qid();
                if (qid == null || !qid.matches("Q\\d+")) {
                    continue;   // not a saved entity — don't inflate the count
                }
                qidsByType.computeIfAbsent(
                        o.typeName(), k -> new java.util.HashSet<>()).add(qid);
            }
        }
        if (qidsByType.isEmpty()) {
            return "Generated instances  (" + lastRun.size() + ")";
        }
        int total = qidsByType.values().stream().mapToInt(java.util.Set::size).sum();
        String breakdown = qidsByType.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .map(e -> e.getKey() + " " + e.getValue().size())
                .collect(java.util.stream.Collectors.joining(", "));
        return "Generated instances — " + breakdown
                + "  (" + total + " distinct)";
    }

    private void wireActions() {
        SwingQueryRunner queryRunner = querySession.runner();

        queryRunner.registerCancelButton(cancelButton);
        processRunner.registerCancelButton(cancelButton);
        processRunner.registerRunButton(generateDomainButton);
        queryRunner.registerRunButton(showGeneratedSourceButton);
        queryRunner.cancelAction(client::cancelCurrentQuery);

        sourceWorkbench.setQueryRunner(queryRunner);
        sourceWorkbench.log(logWindow::info);

        sourceWorkbench.afterChange(v -> {
            classModelPanel.refresh();
            // Keep the (possibly open) model graph in sync with edits.
            if (graphWindow != null && graphWindow.isVisible()) {
                graphPanel.setModel(projectModel);
            }
        });

        sourceWorkbench.afterApplyField(f -> {
            classModelPanel.refresh();
            classModelPanel.selectField(f);
            sourceWorkbench.edit(f);
        });

        // A field added from a tool in the Explorer window is otherwise
        // invisible — bring the main config window forward and say where it went
        // (only on an explicit add, NOT on the applyEdits a Discover run does,
        // which used to steal focus from the Explorer window).
        sourceWorkbench.onFieldAddedFromTool(f -> {
            GeneratedClassModel owner = activeClass();
            logWindow.info("Added field \"" + f.name() + "\""
                    + (owner == null ? "" : " on class \"" + owner.className() + "\"")
                    + " — shown in the main window.");
            toFront();
            requestFocus();
        });

        classModelPanel.addTreeSelectionListener(e -> {
            sourceWorkbench.edit(classModelPanel.selectedUserObject());
            // Per-class depth: show the newly-selected class's saved depth.
            syncDepthSpinnerToActiveClass();
            // Mirror the selection onto the graph (the two-way "connection").
            GeneratedClassModel sel = activeClass();
            graphPanel.setSelectedClass(sel == null ? "" : sel.className());
        });

        // Store depth edits onto the currently-selected class.
        depthSpinner.addChangeListener(e -> {
            if (syncingDepth) return;
            GeneratedClassModel c = activeClass();
            if (c != null) {
                c.generationDepth(((Number) depthSpinner.getValue()).intValue());
            }
        });

        queryRunner.wireButton(
                generateButton,
                this::acceptGenerationRun,
                () -> {
                    sourceWorkbench.applyEdits();
                    GeneratedProjectModel rooted = modelRootedAtSelected();
                    String problem = membershipProblem(rooted.rootClass());
                    if (problem != null) {
                        warnNothingToGenerate(problem);
                        return null; // don't run — avoids a silent empty panel
                    }
                    return new GenerateInstancesQuery(
                            rooted,
                            ((Number) depthSpinner.getValue()).intValue());
                },
                this::reportGenerationError);

        generateDomainButton.addActionListener(e -> {
            if (processRunner.isRunning() || queryRunner.isRunning()) {
                return;
            }
            try {
                    sourceWorkbench.applyEdits();
                    // Per-class depth (saved on each class) is used; sync the
                    // active class's spinner value first.
                    GeneratedClassModel active = activeClass();
                    if (active != null) {
                        active.generationDepth(
                                ((Number) depthSpinner.getValue()).intValue());
                    }
                    // If NO class can be populated, say so instead of opening an
                    // empty instances panel.
                    boolean anyGeneratable = projectModel.classes().stream()
                            .anyMatch(c -> membershipProblem(c) == null);
                    if (!anyGeneratable) {
                        warnNothingToGenerate(membershipProblem(
                                projectModel.rootClass()));
                        return;
                    }
                    processRunner.run(
                            new GenerateDomainProcess(projectModel.copy()),
                            outcome -> {
                                if (outcome.usefulResult().isPresent()) {
                                    acceptGenerationRun(outcome.usefulResult().get());
                                }
                                if (outcome.status() == ProcessStatus.FAILED
                                        && outcome.error() instanceof Exception ex) {
                                    reportGenerationError(ex);
                                }
                            },
                            error -> reportGenerationError(
                                    error instanceof Exception ex
                                            ? ex : new RuntimeException(error)));
            } catch (Exception ex) {
                reportGenerationError(ex);
            }
        });

        queryRunner.wireButton(
                remapButton,
                this::acceptGenerationRun,
                () -> {
                    sourceWorkbench.applyEdits();
                    if (lastRun == null) {
                        warnNothingToGenerate(
                                "Nothing to remap yet — run Generate domain (or "
                                        + "instances) first.");
                        return null;
                    }
                    // Reuse the last download; re-materialize with the current model
                    // so canonicalization / display-name edits apply without a
                    // re-fetch. Valid for local edits (same extraction).
                    return new RemapInstancesQuery(lastRun, projectModel.copy());
                },
                this::reportGenerationError);

        showInstancesButton.addActionListener(e -> showInstancesWindow());
        showStatementsButton.addActionListener(e -> showStatementsWindow(queryRunner));

        showExplorerButton.addActionListener(e -> showExplorerWindow());
        showGraphButton.addActionListener(e -> showGraphWindow());
        showSelectionsButton.addActionListener(e -> showSelectionsWindow());

        // Wire the WikiProject seed panel to the selected class: pick one entity
        // as the membership type, or add/replace the class's seed-QID set.
        // WikiProject + Explore share the same targets: the active class's
        // seed-QID set (instances) or its membership type.
        WikiProjectSeedPanel wp = sourceWorkbench.wikiProjectPanel();
        wp.onUseAsSourceQid(this::useSourceQid);
        wp.onAddSeedQids(qids -> addSeedQids(qids, false));
        wp.onReplaceSeedQids(qids -> addSeedQids(qids, true));

        ExploreByExamplePanel ex = sourceWorkbench.explorePanel();
        ex.onAddSeedQids(qids -> addSeedQids(qids, false));
        ex.onAddRelationTargets(this::addRelationTargets);
        ex.onUseAsSourceQid(this::useSourceQid);

        CategorySeedPanel cat = sourceWorkbench.categoryPanel();
        cat.onUseAsSourceQid(this::useSourceQid);
        cat.onAddSeedQids(qids -> addSeedQids(qids, false));
        cat.onReplaceSeedQids(qids -> addSeedQids(qids, true));

        sourceWorkbench.onShowHelperTools(this::showExplorerWindow);

        domainBox.addActionListener(e -> {
            if (updatingDomainBox) return;
            Object sel = domainBox.getSelectedItem();
            if (sel != null && !sel.toString().equals(projectModel.name())) {
                switchToDomain(sel.toString());
            }
        });
        newDomainButton.addActionListener(e -> newDomain());
        renameDomainButton.addActionListener(e -> renameDomain());
        deleteDomainButton.addActionListener(e -> deleteDomain());

        loadModelButton.addActionListener(e -> loadModel());

        loadSavedButton.addActionListener(e -> loadSavedInstances());

        saveEverythingButton.addActionListener(e -> saveEverything());

        showGeneratedSourceButton.addActionListener(e ->
                                                            showGeneratedSource());

        showRuleTreeButton.addActionListener(e -> showRuleTree());
        showGuideButton.addActionListener(e -> showGuide());

        showQueryLogsButton.addActionListener(e -> querySession.showLogs(this));

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
                // Generation runs on a COPY of the model, so a descriptive vocabulary
                // built from the loaded data (e.g. NomineeType, WorkGenre) lands on
                // that copy and is otherwise lost. Fold the built values back into the
                // live model so "Save domain" persists them — only filling vocabularies
                // that are still EMPTY here, never overwriting authored constraint
                // vocabularies (e.g. OscarCategories).
                int filledVocabs = mergeBuiltVocabularies(run.modelSnapshot());
                if (filledVocabs > 0) {
                    classModelPanel.refresh();
                    logWindow.info(filledVocabs + " vocabulary(ies) filled from the "
                            + "generated data — use \"Save domain\" to persist them.");
                }
                // Do NOT auto-save: generating used to silently overwrite the
                // project snapshot (a greekmyth run clobbered constellations).
                // The run is held in memory + shown here; persisting is explicit
                // and confirmed via "Save domain".
                logWindow.info("Generated " + run.size()
                        + " objects (in memory — use \"Save domain\" to persist "
                        + "to " + snapshotFile().getName() + ").");
                reportNameCollisions(run);
                showInstancesWindow(); // pop the results window on a fresh run
            } else {
                instancesPanel.clear();
            }
        });
    }

    /** Fold vocabularies BUILT during generation (from a referenced field's loaded
     *  values) back into the live model, so they survive "Save domain". Fills only a
     *  vocabulary that is still empty here — a built descriptive vocab (NomineeType,
     *  WorkGenre) — and never overwrites an authored constraint vocab that already has
     *  values (OscarCategories). Creates the vocab locally if generation invented it.
     *  @return how many vocabularies were newly filled/created. */
    private int mergeBuiltVocabularies(GeneratedProjectModel built) {
        return mergeBuiltVocabularies(built, projectModel);
    }

    /** Reflect the generation-derived DESCRIPTIVE vocabularies (NomineeType, WorkGenre)
     *  into the live model so this session shows them. They are derived, so we REFRESH
     *  (overwrite) — a stale value must not linger — but only the descriptive targets;
     *  an authored constraint vocabulary (OscarCategories) is never touched. The values
     *  are not persisted: {@code saveDomain} strips them and load re-derives from the
     *  snapshot. Package-private + static so it is unit-testable without a live frame. */
    static int mergeBuiltVocabularies(
            GeneratedProjectModel built, GeneratedProjectModel into) {
        if (built == null || into == null) {
            return 0;
        }
        java.util.Set<String> descriptive =
                wikidata.explore.transform.DescriptiveVocabularyBuild.targets(into);
        int filled = 0;
        for (String name : descriptive) {
            if (!(built.findSelection(name) instanceof VocabularySelection src)) {
                continue;
            }
            java.util.List<String> values = src.valueQids() == null
                    ? java.util.List.of() : src.valueQids();
            Selection existing = into.findSelection(name);
            if (existing instanceof VocabularySelection target) {
                target.valueQids(new java.util.ArrayList<>(values));   // refresh
            } else if (existing == null) {
                VocabularySelection created = new VocabularySelection(name);
                created.valueQids(new java.util.ArrayList<>(values));
                into.addSelection(created);
            } else {
                continue;
            }
            filled++;
        }
        return filled;
    }

    // Greek myth (and history generally) has many distinct entities sharing a
    // name (5 Agenors, 5 Lycuses, …). Make that explicit after a run: list the
    // names that map to >1 QID, so the user can disambiguate or exclude.
    private void reportNameCollisions(GenerationRun run) {
        lastCollisions = java.util.List.of();
        updateNameCollisionsButton();
        if (run == null || run.dynamicObjects() == null) {
            return;
        }
        java.util.Map<String, java.util.LinkedHashSet<String>> byName =
                new java.util.LinkedHashMap<>();
        for (WikidataDynamicObject o : run.dynamicObjects()) {
            if (o == null) {
                continue;
            }
            String name = o.getDisplayName();
            String qid = o.qid();
            if (name == null || name.isBlank() || qid == null || qid.isBlank()) {
                continue;
            }
            // Only real ENTITIES (pure Q-ids). Reified statement atoms
            // (Q123-UUID / Q123__Q456) are named by a shared field on purpose
            // (e.g. a Nomination shows its nominee), so a person with many
            // nominations would otherwise look like a huge "collision".
            if (!qid.matches("Q\\d+")) {
                continue;
            }
            byName.computeIfAbsent(name, k -> new java.util.LinkedHashSet<>()).add(qid);
        }
        java.util.List<java.util.Map.Entry<String, java.util.LinkedHashSet<String>>> collisions =
                byName.entrySet().stream()
                        .filter(e -> e.getValue().size() > 1)
                        .sorted((a, b) -> Integer.compare(
                                b.getValue().size(), a.getValue().size()))
                        .toList();
        if (collisions.isEmpty()) {
            return;
        }
        int instances = collisions.stream().mapToInt(e -> e.getValue().size()).sum();

        // Structured, collapsible entry — one row per colliding name (name ×count),
        // biggest first — instead of one blob listing every QID (which buried the
        // rest of the log). The QIDs themselves are inspectable, clickable, via the
        // "Name collisions" button; the row detail keeps them collapsed.
        int cap = 100;
        java.util.List<wikidata.explore.query.swing.WorkflowLogWindow.Row> rows =
                new java.util.ArrayList<>();
        int shown = 0;
        for (var e : collisions) {
            if (shown++ >= cap) {
                break;
            }
            int n = e.getValue().size();
            // Name + count only; no QID dump (that swamped the row). The QIDs are
            // clickable via the "Name collisions" button.
            rows.add(new wikidata.explore.query.swing.WorkflowLogWindow.Row(
                    e.getKey(), n + " entities share this name", ""));
        }
        if (collisions.size() > cap) {
            rows.add(new wikidata.explore.query.swing.WorkflowLogWindow.Row(
                    "… and " + (collisions.size() - cap) + " more name(s)",
                    "open \"Name collisions\" to see all", ""));
        }
        logWindow.structuredEntry(
                "⚠ " + collisions.size() + " name collision(s) — "
                        + instances + " instances share a name",
                "Ambiguous quiz answers — disambiguate or Exclude types; open "
                        + "\"Name collisions (" + collisions.size() + ")\" to inspect.",
                rows);

        // Map each generated instance by its QID, so a colliding entry links to
        // the actual entity used in the instances (click through), falling back
        // to a Source (QID + wiki link) for QIDs that weren't materialized.
        java.util.Map<String, objectview.Viewable> byQid = new java.util.HashMap<>();
        if (run.instances() != null) {
            for (objectview.Viewable q : run.instances()) {
                if (q != null && q.getIdentifier() != null) {
                    byQid.putIfAbsent(q.getIdentifier(), q);
                }
            }
        }

        java.util.List<NameCollision> cards = new java.util.ArrayList<>();
        for (var e : collisions) {
            java.util.List<objectview.Viewable> entities = new java.util.ArrayList<>();
            for (String qid : e.getValue()) {
                objectview.Viewable used = byQid.get(qid);
                entities.add(used != null ? used : new quiz.source.WikidataViewable(qid));
            }
            cards.add(new NameCollision(e.getKey(), entities));
        }
        lastCollisions = cards;
        updateNameCollisionsButton();
    }

    private void updateNameCollisionsButton() {
        int n = lastCollisions.size();
        nameCollisionsButton.setText(
                n == 0 ? "Name collisions" : "Name collisions (" + n + ")");
        nameCollisionsButton.setEnabled(n > 0);
    }

    private void showNameCollisions() {
        if (lastCollisions.isEmpty()) {
            return;
        }
        CardListView view = new CardListView();
        // Share the instances panel's render context so clicking a colliding
        // entity navigates to (focuses + scrolls to) its card in the instances
        // window instead of opening a detached copy.
        RenderContext shared = instancesPanel.activeRenderContext();
        if (shared != null) {
            view.setRenderContext(shared);
            view.setInPlaceNavigation(true);
        }
        for (NameCollision c : lastCollisions) {
            view.addViewable(c);
        }
        view.show("Name collisions (" + lastCollisions.size() + ")", 1);
    }

    // Returns a human-readable reason the class can't be populated, or null if it
    // can. A class is generatable with a membership target (sourceQid), extra type
    // QIDs, or an explicit seed-QID set — same rule as GenerateDomainQuery. The
    // message is specific: a set relation with a blank target is the common trap
    // (e.g. P1411 "nominated for" with no award to point at).
    private String membershipProblem(GeneratedClassModel c) {
        if (c == null) {
            return "No class selected.";
        }
        // A statement class is populated by reification (its subjects come from a
        // source class or are discovered), not by a membership query — so it needs
        // no membership target. GenerateDomainQuery reifies it regardless.
        if (c.reifiesStatements()) {
            return null;
        }
        var m = c.effectiveInstanceMapping(projectModel);
        boolean hasTarget = m != null && !m.sourceQid().isBlank();
        boolean hasExtraTypes = m != null && !m.additionalTypeQids().isEmpty();
        boolean hasSeeds = !c.seedQids().isEmpty();
        if (hasTarget || hasExtraTypes || hasSeeds) {
            return null;
        }
        String rel = m == null || m.propertyPid().isBlank() ? "P31" : m.propertyPid();
        if (!rel.equals("P31")) {
            return "Class \"" + c.className() + "\" has relation property " + rel
                    + " but no target. Set the \"Relation target\" (the entity the "
                    + "relation points to — e.g. the award), or add Seed QIDs.";
        }
        return "Class \"" + c.className() + "\" has no membership type (Wikidata "
                + "type/class) and no Seed QIDs — nothing to generate. Set a "
                + "type/class QID (or a relation + target), or add Seed QIDs.";
    }

    private GeneratedClassModel classByName(String name) {
        if (name == null) {
            return null;
        }
        for (GeneratedClassModel c : projectModel.classes()) {
            if (c != null && name.equals(c.className())) {
                return c;
            }
        }
        return null;
    }

    private void warnNothingToGenerate(String message) {
        logWindow.info(message);
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this, message, "Nothing to generate",
                JOptionPane.INFORMATION_MESSAGE));
    }

    // Guided build-steps for the active class: the model-builder explaining itself.
    private void showGuide() {
        if (guideWindow == null) {
            guidePanel = new ModelingGuidePanel(this::guideContext);
            guideWindow = new JFrame("Build guide");
            guideWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            guideWindow.setContentPane(guidePanel);
            guideWindow.setSize(460, 460);
            guideWindow.setLocationRelativeTo(this);
        }
        guidePanel.refresh();
        guideWindow.setVisible(true);
        guideWindow.toFront();
    }

    // The decision context = current project + active class. (Reify/invert constructs
    // now live on the model + compiled pipeline, so there is no separate transform.)
    private wikidata.explore.advisor.DecisionContext guideContext() {
        GeneratedClassModel active = activeClass();
        return new wikidata.explore.advisor.DecisionContext(
                projectModel, active, new wikidata.explore.transform.TransformConfig());
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
                    + "usually needs a valid object type (or none -> Viewable).";
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
    // On startup, restore the saved project model (config) if one exists, so
    // edits persist across restarts rather than reverting to the demo. Best
    // effort: a missing/corrupt file just leaves the demo in place.
    // ------------------------------------------------------------------
    // Domains: the project as a whole is one domain (name + its classes),
    // keyed by name. The registry is the list of domains; New/Rename/Delete
    // manage them and "Save domain" persists the selected one.
    // ------------------------------------------------------------------

    // Repopulate the combo from the registry plus the current (possibly unsaved)
    // domain, selecting the current one. Guarded so it doesn't fire a switch.
    private void refreshDomainBox() {
        updatingDomainBox = true;
        try {
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            names.add(projectModel.name());
            try {
                for (quiz.DatasetRegistry.Dataset d
                        : quiz.DatasetRegistry.load().datasets()) {
                    if (d.name() != null && !d.name().isBlank()) {
                        names.add(d.name());
                    }
                }
            } catch (Exception ignore) {
                // a missing/corrupt registry just means only the current domain
            }
            domainBox.removeAllItems();
            for (String n : names) {
                domainBox.addItem(n);
            }
            domainBox.setSelectedItem(projectModel.name());
        } finally {
            updatingDomainBox = false;
        }
    }

    private quiz.DatasetRegistry.Dataset findDataset(String name) {
        try {
            for (quiz.DatasetRegistry.Dataset d
                    : quiz.DatasetRegistry.load().datasets()) {
                if (name.equals(d.name()) || domainKey(name).equals(d.key())) {
                    return d;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private File domainModelFile(String name) {
        quiz.DatasetRegistry.Dataset d = findDataset(name);
        if (d != null && !d.modelPath().isBlank()) {
            return new File(d.modelPath());
        }
        String k = domainKey(name);
        return new File(Constants.wikidataDataDirectory + k + "/" + k + ".model.json");
    }

    private boolean confirmDiscardChanges(String title) {
        int c = JOptionPane.showConfirmDialog(
                this,
                "Discard any unsaved changes to \"" + projectModel.name()
                        + "\" and continue?\n"
                        + "(Use \"Save domain\" first to keep them.)",
                title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        return c == JOptionPane.OK_OPTION;
    }

    // Switch to another domain selected in the combo: confirm, then load its
    // saved model. Reverts the combo selection if it can't proceed.
    private void switchToDomain(String name) {
        File model = domainModelFile(name);
        if (model == null || !model.isFile()) {
            JOptionPane.showMessageDialog(this,
                    "No saved model for domain \"" + name + "\" at\n"
                            + (model == null ? "(unknown)" : model.getPath()),
                    "Cannot switch domain", JOptionPane.WARNING_MESSAGE);
            refreshDomainBox();
            return;
        }
        if (!confirmDiscardChanges("Switch domain")) {
            refreshDomainBox();
            return;
        }
        doLoadDomain(model);
        refreshDomainBox();
    }

    // Loads a domain's model in place (no confirm); shared by switch and the
    // post-delete fallback.
    private void doLoadDomain(File model) {
        try {
            projectModel.copyContentsFrom(
                    new GeneratedProjectModelStore().load(model));
            lastRun = null;
            instancesPanel.clear();
            classModelPanel.refresh();
            sourceWorkbench.edit(projectModel.rootClass());
            syncDepthSpinnerToActiveClass();
            logWindow.info("Loaded domain \"" + projectModel.name() + "\".");
        } catch (Exception ex) {
            reportGenerationError(ex);
        }
    }

    private void newDomain() {
        String name = JOptionPane.showInputDialog(
                this, "New domain name:", "New domain",
                JOptionPane.QUESTION_MESSAGE);
        if (name == null) {
            return;
        }
        name = name.trim();
        if (name.isBlank()) {
            return;
        }
        if (findDataset(name) != null) {
            int c = JOptionPane.showConfirmDialog(this,
                    "A domain \"" + name + "\" already exists. Switch to it?",
                    "Domain exists", JOptionPane.OK_CANCEL_OPTION);
            if (c == JOptionPane.OK_OPTION) {
                switchToDomain(name);
            } else {
                refreshDomainBox();
            }
            return;
        }
        if (!confirmDiscardChanges("New domain")) {
            refreshDomainBox();
            return;
        }

        GeneratedProjectModel fresh = new GeneratedProjectModel();
        fresh.name(name);
        // Start with one neutrally-named root class the user then configures.
        fresh.rootClass().className(
                GeneratedViewableSourceGenerator.sanitizeClassName(name));
        projectModel.copyContentsFrom(fresh);
        lastRun = null;
        instancesPanel.clear();
        classModelPanel.refresh();
        sourceWorkbench.edit(projectModel.rootClass());
        syncDepthSpinnerToActiveClass();
        refreshDomainBox();
        logWindow.info("Created new domain \"" + name + "\". Configure its "
                + "classes, then \"Save domain\" to persist + register it.");
    }

    // Rename in place: move the domain folder and re-key the files inside, then
    // update the registry entry (if any). Nothing destructive beyond the move.
    private void renameDomain() {
        String oldName = projectModel.name();
        String oldKey = projectKey();
        String name = (String) JOptionPane.showInputDialog(
                this, "Rename domain:", "Rename domain",
                JOptionPane.QUESTION_MESSAGE, null, null, oldName);
        if (name == null) {
            return;
        }
        name = name.trim();
        if (name.isBlank() || name.equals(oldName)) {
            return;
        }

        String newKey = domainKey(name);
        File oldDir = new File(Constants.wikidataDataDirectory + oldKey + "/");
        File newDir = new File(Constants.wikidataDataDirectory + newKey + "/");
        if (!newKey.equals(oldKey) && newDir.exists()) {
            JOptionPane.showMessageDialog(this,
                    "A domain folder already exists at\n" + newDir.getPath()
                            + "\nChoose a different name.",
                    "Rename domain", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            if (!newKey.equals(oldKey) && oldDir.isDirectory()) {
                java.nio.file.Files.move(oldDir.toPath(), newDir.toPath());
                for (String ext : new String[]{
                        ".model.json", ".ruletree.json", ".snapshot.json"}) {
                    File from = new File(newDir, oldKey + ext);
                    File to = new File(newDir, newKey + ext);
                    if (from.isFile()) {
                        java.nio.file.Files.move(from.toPath(), to.toPath());
                    }
                }
            }
            projectModel.name(name); // projectKey()/file paths now use newKey
            rekeyRegistry(oldKey);
            classModelPanel.refresh();
            refreshDomainBox();
            logWindow.info("Renamed domain \"" + oldName + "\" -> \"" + name + "\".");
        } catch (Exception ex) {
            reportGenerationError(ex);
        }
    }

    // Point an existing registry entry (found by its old key) at the renamed
    // domain's new name/key/paths. No entry yet = nothing to do (the next
    // "Save domain" registers it).
    private void rekeyRegistry(String oldKey) {
        try {
            quiz.DatasetRegistry reg = quiz.DatasetRegistry.load();
            for (quiz.DatasetRegistry.Dataset d : reg.datasets()) {
                if (oldKey.equals(d.key())) {
                    d.name(projectModel.name());
                    d.key(projectKey());
                    d.modelPath(modelFile().getPath());
                    d.ruletreePath(ruleTreeFile().getPath());
                    d.snapshotPath(snapshotFile().getPath());
                    reg.save();
                    return;
                }
            }
        } catch (Exception ex) {
            logWindow.info("Could not re-key registry: " + ex.getMessage());
        }
    }

    private void deleteDomain() {
        String name = projectModel.name();
        String key = projectKey();
        File dir = projectDataDir();
        int c = JOptionPane.showConfirmDialog(this,
                "Delete domain \"" + name + "\"?\n\n"
                        + "Removes it from the registry and deletes:\n"
                        + dir.getPath() + "\n\nThis cannot be undone.",
                "Delete domain",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (c != JOptionPane.OK_OPTION) {
            refreshDomainBox();
            return;
        }
        try {
            quiz.DatasetRegistry reg = quiz.DatasetRegistry.load();
            reg.datasets().removeIf(x -> key.equals(x.key()));
            reg.save();
            deleteRecursively(dir);
            logWindow.info("Deleted domain \"" + name + "\".");
        } catch (Exception ex) {
            reportGenerationError(ex);
        }

        // Switch to another registered domain, or fall back to a fresh demo.
        String next = null;
        try {
            for (quiz.DatasetRegistry.Dataset d
                    : quiz.DatasetRegistry.load().datasets()) {
                if (d.name() != null && !d.name().isBlank()) {
                    next = d.name();
                    break;
                }
            }
        } catch (Exception ignore) {
        }
        if (next != null) {
            doLoadDomain(domainModelFile(next));
        } else {
            projectModel.copyContentsFrom(GeneratedProjectModel.constellationDemo());
            lastRun = null;
            instancesPanel.clear();
            classModelPanel.refresh();
            sourceWorkbench.edit(projectModel.rootClass());
            syncDepthSpinnerToActiveClass();
        }
        refreshDomainBox();
    }

    private static void deleteRecursively(File f) throws java.io.IOException {
        if (f == null || !f.exists()) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk =
                     java.nio.file.Files.walk(f.toPath())) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .map(java.nio.file.Path::toFile)
                .forEach(File::delete);
        }
    }

    private void loadSavedModelIfPresent() {
        File f = modelFile();
        if (!f.isFile()) {
            return;
        }
        try {
            projectModel.copyContentsFrom(new GeneratedProjectModelStore().load(f));
            classModelPanel.refresh();
            syncDepthSpinnerToActiveClass();
        } catch (Exception ex) {
            System.err.println("Could not load saved model " + f.getPath()
                    + ": " + ex.getMessage());
        }
    }

    // Loads a project model (config) from a chosen *.model.json, replacing the
    // current one. (Instances are reloaded separately via "Load saved".)
    // A domain is one folder under data/wikidata/ holding its .model/.ruletree/
    // .snapshot files; the user picks the FOLDER (files are shown but not
    // selectable — which file is a technical detail) and we derive the file.
    private File chooseDomainDir(String title) {
        JFileChooser chooser =
                new JFileChooser(new File(Constants.wikidataDataDirectory));
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        File suggested = projectDataDir();
        if (suggested.isDirectory()) {
            chooser.setSelectedFile(suggested);
        }
        return chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
                ? chooser.getSelectedFile()
                : null;
    }

    private static File findInDir(File dir, String suffix) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] fs = dir.listFiles((d, n) -> n.endsWith(suffix));
        return fs == null || fs.length == 0 ? null : fs[0];
    }

    // The registry entry whose snapshot is this file (for the model-drift check).
    private static quiz.DatasetRegistry.Dataset datasetForSnapshot(File file) {
        try {
            File want = file.getCanonicalFile();
            for (quiz.DatasetRegistry.Dataset d
                    : quiz.DatasetRegistry.load().datasets()) {
                if (!d.snapshotPath().isBlank()
                        && new File(d.snapshotPath()).getCanonicalFile().equals(want)) {
                    return d;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private void loadModel() {
        File dir = chooseDomainDir("Load domain — pick its folder under data/wikidata/");
        if (dir == null) {
            return;
        }
        File model = findInDir(dir, ".model.json");
        if (model == null) {
            JOptionPane.showMessageDialog(this,
                    "No *.model.json found in\n" + dir.getPath(),
                    "Load domain", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            projectModel.copyContentsFrom(
                    new GeneratedProjectModelStore().load(model));
            classModelPanel.refresh();
            sourceWorkbench.edit(projectModel.rootClass());
            syncDepthSpinnerToActiveClass();
            refreshDomainBox();
            logWindow.info("Loaded domain model from " + model.getName());
        } catch (Exception ex) {
            reportGenerationError(ex);
        }
    }

    private void loadSavedInstances() {
        File dir = chooseDomainDir("Load saved instances — pick the domain folder");
        if (dir == null) {
            return;
        }
        File file = findInDir(dir, ".snapshot.json");
        if (file == null) {
            JOptionPane.showMessageDialog(this,
                    "No *.snapshot.json found in\n" + dir.getPath()
                            + "\n(generate + save the domain first)",
                    "Load saved", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            sourceWorkbench.applyEdits();

            // Drift guard: warn if these instances were generated from a
            // different model version than the one we'll map them through.
            quiz.DatasetRegistry.Dataset ds = datasetForSnapshot(file);
            if (ds != null && !ds.modelSignature().isEmpty()
                    && !ds.modelSignature().equals(modelSignature(projectModel))) {
                JOptionPane.showMessageDialog(this,
                        "These saved instances were generated from a DIFFERENT model\n"
                        + "version than the current model. Fields may not match —\n"
                        + "\"Generate instances\" to refresh them.",
                        "Instances may be stale", JOptionPane.WARNING_MESSAGE);
            }

            GeneratedProjectModel snapshot = projectModel.copy();
            List<WikidataDynamicObject> objects =
                    new WikidataDynamicObjectJsonStore().load(file);

            // Apply the current model's canonicalization to the loaded pool, so a
            // display-name spec set/edited after this snapshot was saved takes
            // effect on load (e.g. a Nomination shows its nominee) — no re-download.
            wikidata.explore.transform.Canonicalization.apply(snapshot, objects,
                    wikidata.explore.extract.GenerationLog.of(logWindow::info));

            // Derive the descriptive vocabularies (NomineeType, WorkGenre) from the
            // loaded pool — they are not persisted, so load is where they come back.
            // acceptGenerationRun then folds them into the live model for display.
            wikidata.explore.transform.DescriptiveVocabularyBuild.apply(snapshot, objects,
                    wikidata.explore.extract.GenerationLog.of(logWindow::info));

            GenerationPipeline pipeline = new GenerationPipeline();
            RuleNode plan = pipeline.plan(snapshot);
            GeneratedViewableRuntime runtime = pipeline.buildRuntime(snapshot);
            List<Viewable> instances =
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

    // Make the given entity the selected class's membership type. Shared by the
    // WikiProject and Explore panels.
    private void useSourceQid(String qid, String label) {
        if (qid == null || !qid.matches("Q\\d+")) {
            return;
        }
        GeneratedClassModel c = activeClass();
        c.instanceMapping().sourceQid(qid);
        c.instanceMapping().sourceLabel(label == null ? "" : label);
        sourceWorkbench.edit(c);
        classModelPanel.refresh();
        logWindow.info("Set source of \"" + c.className() + "\" to "
                + qid + (label == null ? "" : " (" + label + ")") + ".");
    }

    // Fill the selected class's seed-QID set (these entities become its
    // instances). Shared by the WikiProject and Explore panels.
    private void addSeedQids(List<String> qids, boolean replace) {
        GeneratedClassModel c = activeClass();
        if (replace) {
            c.seedQids().clear();
        }
        int added = 0;
        for (String qid : qids) {
            if (qid != null && qid.matches("Q\\d+") && !c.seedQids().contains(qid)) {
                c.seedQids().add(qid);
                added++;
            }
        }
        sourceWorkbench.edit(c);
        classModelPanel.refresh();
        logWindow.info((replace ? "Replaced" : "Added") + " seed QIDs on \""
                + c.className() + "\": " + added + " (total "
                + c.seedQids().size() + "). Empty the Wikidata type to use only "
                + "these as instances.");
    }

    // Add explored members to the active class's membership-relation targets
    // (the "Also include types" set), so all targets live in one place — no need
    // to seed-and-cut/paste, and the main "Relation target" field can stay blank.
    private void addRelationTargets(List<String> qids) {
        GeneratedClassModel c = activeClass();
        var targets = c.instanceMapping().additionalTypeQids();
        int added = 0;
        for (String qid : qids) {
            if (qid != null && qid.matches("Q\\d+") && !targets.contains(qid)) {
                targets.add(qid);
                added++;
            }
        }
        sourceWorkbench.edit(c);
        classModelPanel.refresh();
        logWindow.info("Added " + added + " relation target(s) to \""
                + c.className() + "\" (total " + targets.size()
                + "). They join the membership relation (" + c.instanceMapping()
                .propertyPid() + ") as additional targets.");
    }

    // The class the toolbar actions operate on: the one selected in the class
    // tree (or the owner of a selected field), else the root class.
    private GeneratedClassModel activeClass() {
        return classModelPanel.selectedClassOrRoot();
    }

    // Reflects the active class's saved depth in the spinner without the change
    // listener writing it straight back.
    private void syncDepthSpinnerToActiveClass() {
        GeneratedClassModel c = activeClass();
        if (c == null) {
            return;
        }
        syncingDepth = true;
        try {
            depthSpinner.setValue(c.generationDepth());
        } finally {
            syncingDepth = false;
        }
    }

    // A detached copy of the project rooted at the selected class, so Generate/
    // Show source/Show rule tree target whatever class is selected — not always
    // the root. (Child-object types are derived from field configs, so the
    // single rooted class compiles and generates standalone.)
    private GeneratedProjectModel modelRootedAtSelected() {
        GeneratedProjectModel sub = projectModel.copy();
        String target = activeClass().className();
        for (GeneratedClassModel c : sub.classes()) {
            if (c.className().equals(target)) {
                if (c != sub.rootClass()) {
                    sub.rootClass(c);
                }
                break;
            }
        }
        return sub;
    }

    // The project's data directory, keyed off its name so it lands where the
    // web client looks: "Constellations" -> data/wikidata/constellations/,
    // i.e. the exact folder the web GeneratedSource and "Load saved" read.
    private String projectKey() {
        return domainKey(projectModel.name());
    }

    // The filesystem key for a domain name (folder + file prefix under
    // data/wikidata/), e.g. "Greek Myth" -> "greekmyth".
    private static String domainKey(String name) {
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
    // A model's generation signature: the rule tree it compiles to is exactly
    // what drives generation, so two models with the same signature produce the
    // same instances. Used to detect a model drifting past its saved snapshot.
    private static String modelSignature(GeneratedProjectModel model) {
        try {
            RuleNode root = RuleTreeCompiler.compileProject(model);
            String json = new RuleTreeSerializer().mapper()
                    .writeValueAsString(RuleTreeConfig.of(root));
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return ""; // best-effort: no signature -> no drift check
        }
    }

    // The signature of the model the in-memory instances (lastRun) were
    // generated from, or "" if there are none.
    private String generatedInstancesSignature() {
        return lastRun != null && lastRun.modelSnapshot() != null
                ? modelSignature(lastRun.modelSnapshot())
                : "";
    }

    // The distinct class-types stamped on a set of generated objects.
    private static java.util.Set<String> stampedTypes(
            java.util.Collection<WikidataDynamicObject> objs) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (objs != null) {
            for (WikidataDynamicObject o : objs) {
                if (o != null && o.typeName() != null && !o.typeName().isBlank()) {
                    out.add(o.typeName());
                }
            }
        }
        return out;
    }

    // The types already present in the saved snapshot (to detect a single-class
    // run about to overwrite a multi-class one).
    private java.util.Set<String> snapshotTypesOnDisk() {
        try {
            return stampedTypes(
                    new WikidataDynamicObjectJsonStore().load(snapshotFile()));
        } catch (Exception e) {
            return java.util.Set.of();
        }
    }

    private void saveEverything() {
        sourceWorkbench.applyEdits();

        // Confirm BEFORE writing — show the exact paths and what each will get,
        // so Escape actually cancels (the old dialog appeared after the files
        // were already written).
        boolean haveInstances = lastRun != null
                && lastRun.dynamicObjects() != null
                && !lastRun.dynamicObjects().isEmpty();

        // Drift guard: the snapshot we'd write came from lastRun's model; if the
        // current model has changed since, the saved instances will be stale.
        String runSig = generatedInstancesSignature();
        if (haveInstances && !runSig.isEmpty()
                && !runSig.equals(modelSignature(projectModel))) {
            int d = JOptionPane.showConfirmDialog(this,
                    "The model has changed since these instances were generated.\n"
                    + "The saved snapshot will be STALE (not match the saved model).\n\n"
                    + "Regenerate (Cancel, then \"Generate instances\") before saving,\n"
                    + "or save anyway?",
                    "Model changed since generation",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (d != JOptionPane.OK_OPTION) {
                return;
            }
        }

        // Overwrite guard: a single-class run ("Generate instances") must not
        // silently replace a multi-class snapshot (e.g. Episode, Labour). Warn
        // about types on disk that this run would drop. (Use "Generate domain".)
        if (haveInstances && snapshotFile().isFile()) {
            java.util.Set<String> runTypes = stampedTypes(lastRun.dynamicObjects());
            java.util.Set<String> dropped =
                    new java.util.LinkedHashSet<>(snapshotTypesOnDisk());
            dropped.removeAll(runTypes);
            if (!dropped.isEmpty()) {
                int d = JOptionPane.showConfirmDialog(this,
                        "This run produced only: "
                        + String.join(", ", runTypes) + ".\n"
                        + "Saving will OVERWRITE the snapshot and DROP these "
                        + "existing types: " + String.join(", ", dropped) + ".\n\n"
                        + "Use \"Generate domain\" to keep every class. Save anyway?",
                        "Overwriting a multi-class snapshot",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (d != JOptionPane.OK_OPTION) {
                    return;
                }
            }
        }

        String plan = "Save the domain \"" + projectModel.name()
                + "\" — write these files?\n\n"
                + "Config:    " + modelFile().getPath() + "\n"
                + "Rule tree: " + ruleTreeFile().getPath() + "\n"
                + "Instances: " + (haveInstances
                        ? lastRun.dynamicObjects().size() + " -> " + snapshotFile().getPath()
                        : "(none generated yet — will be skipped)");
        int choice = JOptionPane.showConfirmDialog(
                this, plan, "Save domain",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        StringBuilder report = new StringBuilder();
        try {
            modelFile().getParentFile().mkdirs();

            // Depth is now per-class (saved on each class via the spinner change
            // listener); make sure the active class has the latest spinner value.
            GeneratedClassModel active = activeClass();
            if (active != null) {
                active.generationDepth(((Number) depthSpinner.getValue()).intValue());
            }
            // A DESCRIPTIVE vocabulary (NomineeType, WorkGenre) is derived from the
            // data, not authored — persist it as an EMPTY shell (still declared, so the
            // field target resolves and the tree shows it) and re-derive its values from
            // the snapshot on load. This keeps it from ever being saved stale.
            GeneratedProjectModel toSave = projectModel.copy();
            for (String name
                    : wikidata.explore.transform.DescriptiveVocabularyBuild.targets(toSave)) {
                if (toSave.findSelection(name) instanceof VocabularySelection v) {
                    v.valueQids(new java.util.ArrayList<>());
                }
            }
            new GeneratedProjectModelStore().save(toSave, modelFile());
            report.append("Config:    ").append(modelFile().getPath()).append('\n');

            RuleNode root = RuleTreeCompiler.compileProject(projectModel);
            new RuleTreeSerializer().save(root, ruleTreeFile());
            report.append("Rule tree: ").append(ruleTreeFile().getPath()).append('\n');

            int n = -1;
            if (lastRun != null && lastRun.dynamicObjects() != null
                    && !lastRun.dynamicObjects().isEmpty()) {
                // The run retains the EXACT model snapshot that produced these objects.
                // Declare it into the persisted field graph so null fields and empty typed
                // collections survive without shape placeholders. Do not use projectModel:
                // the user may have explicitly accepted saving a stale run after editing it.
                new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                        lastRun.dynamicObjects(), snapshotFile(),
                        lastRun.modelSnapshot());
                n = lastRun.dynamicObjects().size();
                report.append("Instances: ").append(n)
                      .append(" -> ").append(snapshotFile().getPath()).append('\n');
                // Register the dataset: model + rule-tree + snapshot saved
                // TOGETHER as one consistent triple, so the web serves it and a
                // snapshot is never paired with a mismatched model.
                registerDataset(n, runSig);
                report.append("Registry:  ")
                      .append(quiz.DatasetRegistry.defaultFile().getPath()).append('\n');

                File counts = countsFile();
                appendCountsRecord(counts, lastRun.dynamicObjects());
                report.append("Counts:    ").append(counts.getPath()).append('\n');
            } else {
                report.append("Instances: (none generated yet — run "
                                       + "\"Generate instances\" first; not "
                                       + "registered until model + snapshot are "
                                       + "saved together)\n");
            }

            logWindow.info("Saved domain \"" + projectModel.name() + "\":\n" + report);

            String hint = instanceCountHint(n);
            JOptionPane.showMessageDialog(
                    this,
                    report + (hint.isBlank() ? "" : "\n" + hint),
                    "Saved domain",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            reportGenerationError(ex);
        }
    }

    // The domain's counts log, alongside its snapshot: <domain>.counts.tsv.
    private File countsFile() {
        File snap = snapshotFile();
        return new File(snap.getParentFile(),
                snap.getName().replaceFirst("\\.snapshot\\.json$", "") + ".counts.tsv");
    }

    // Appends ONE timestamped per-class row (distinct-qid counts) per save, so runs
    // can be diffed over time — the stable "perfect" counts vs a drift. Reified records
    // key by their unique statement id, so a class like Nomination counts correctly.
    private void appendCountsRecord(File file, List<WikidataDynamicObject> pool) {
        java.util.Map<String, java.util.Set<String>> byType = new java.util.TreeMap<>();
        for (WikidataDynamicObject o : pool) {
            if (o == null) {
                continue;
            }
            String t = o.typeName();
            String qid = o.qid();
            if (t == null || t.isBlank() || "WikidataDynamicObject".equals(t)
                    || qid == null || qid.isBlank()) {
                continue;
            }
            byType.computeIfAbsent(t, k -> new java.util.HashSet<>()).add(qid);
        }
        List<java.util.Map.Entry<String, java.util.Set<String>>> sorted =
                new java.util.ArrayList<>(byType.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        int total = sorted.stream().mapToInt(e -> e.getValue().size()).sum();

        StringBuilder row = new StringBuilder(
                java.time.LocalDateTime.now().withNano(0).toString());
        row.append("\ttotal=").append(total);
        for (java.util.Map.Entry<String, java.util.Set<String>> e : sorted) {
            row.append('\t').append(e.getKey()).append('=').append(e.getValue().size());
        }
        row.append('\n');
        try {
            boolean fresh = !file.isFile();
            try (java.io.FileWriter w = new java.io.FileWriter(file, true)) {
                if (fresh) {
                    w.write("# " + projectModel.name()
                            + " — per-class distinct-qid counts, one row per save\n");
                }
                w.write(row.toString());
            }
        } catch (Exception ex) {
            logWindow.info("Could not write counts file: " + ex.getMessage());
        }
    }

    // Upserts this project's dataset (model + rule-tree + snapshot triple) into
    // the registry the web reads, so multiple domains coexist and are served.
    private void registerDataset(int instanceCount, String snapshotModelSignature) {
        try {
            quiz.DatasetRegistry reg = quiz.DatasetRegistry.load();
            quiz.DatasetRegistry.Dataset d = new quiz.DatasetRegistry.Dataset();
            d.name(projectModel.name());
            d.key(projectKey());
            d.rootClass(projectModel.rootClass() == null
                    ? "" : projectModel.rootClass().className());
            d.modelPath(modelFile().getPath());
            d.ruletreePath(ruleTreeFile().getPath());
            d.snapshotPath(snapshotFile().getPath());
            d.instanceCount(instanceCount);
            d.modelSignature(snapshotModelSignature == null ? "" : snapshotModelSignature);
            d.savedAt(java.time.LocalDateTime.now().toString());
            java.util.List<String> types = new java.util.ArrayList<>();
            for (GeneratedClassModel c : projectModel.classes()) {
                if (c != null && c.className() != null) types.add(c.className());
            }
            d.types(types);
            reg.upsert(d);
            reg.save();
        } catch (Exception ex) {
            logWindow.info("Could not update dataset registry: " + ex.getMessage());
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
        // Shows the source for the selected class. Prefer the last compiled
        // run's source when it IS that class AND the model hasn't changed since
        // (so you see what actually compiled, incl. errors); otherwise show a
        // fresh preview from the CURRENT model — so applied edits (e.g. a new
        // field) show up without needing to regenerate first.
        sourceWorkbench.applyEdits();

        GeneratedClassModel cls = activeClass();
        String source;
        String title;

        boolean runMatchesClass = lastRun != null && lastRun.runtime() != null
                && lastRun.modelSnapshot() != null
                && lastRun.modelSnapshot().rootClass().className().equals(cls.className());
        boolean modelUnchanged = runMatchesClass
                && modelSignature(projectModel)
                        .equals(modelSignature(lastRun.modelSnapshot()));

        if (modelUnchanged) {
            source = lastRun.runtime().source();
            title = lastRun.runtime().qualifiedClassName();
        } else {
            GeneratedViewableSourceGenerator gen =
                    new GeneratedViewableSourceGenerator(
                            GeneratedViewableSourceGenerator.GENERATED_PACKAGE);
            source = gen.sourceFor(cls, projectModel);
            title = gen.qualifiedClassName(cls)
                    + (runMatchesClass
                            ? "  (preview — model changed since last generate)"
                            : "  (preview - not yet compiled)");
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

        String readable;
        String json;
        try {
            RuleNode root = RuleTreeCompiler.compileClass(activeClass(), projectModel);
            readable = RuleTreeExplainer.explain(root);
            json = new RuleTreeSerializer().mapper()
                    .writeValueAsString(RuleTreeConfig.of(root));
        } catch (Exception ex) {
            reportGenerationError(ex);
            return;
        }

        JTextArea area = new JTextArea(readable, 40, 100);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setEditable(false);
        area.setCaretPosition(0);

        // Default to the plain-language plan; let the curious flip to the raw
        // RuleTreeConfig JSON (handy when reporting a compile bug).
        JToggleButton rawToggle = new JToggleButton("Show raw JSON");
        rawToggle.addActionListener(e -> {
            boolean raw = rawToggle.isSelected();
            area.setText(raw ? json : readable);
            area.setCaretPosition(0);
            rawToggle.setText(raw ? "Show readable plan" : "Show raw JSON");
        });

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        top.add(rawToggle);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);

        JOptionPane.showMessageDialog(
                this,
                panel,
                "Rule tree — " + activeClass().className(),
                JOptionPane.PLAIN_MESSAGE);
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
