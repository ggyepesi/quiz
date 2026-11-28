package domain.workbench.modelbuilder.ui;

import domain.persistence.ProjectSaveGuard;

import domain.build.GraphResults;
import domain.DomainModel;
import domain.transform.app.DomainCatalog;

import domain.build.GraphDiscoveryResultStore;

import domain.datasource.schema.FieldType;

import wikidata.WikidataIds;

import aux.SplitPaneUtils;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import aux.Constants;
import objectview.Viewable;
import domain.compile.codegen.GeneratedViewableRuntime;
import domain.compile.codegen.GeneratedViewableSourceGenerator;
import domain.runtime.WikidataDynamicObject;
import domain.runtime.WikidataDynamicObjectJsonStore;
import domain.build.GenerationRun;
import process.ProcessStatus;
import process.swing.SwingProcessInputHandler;
import process.swing.SwingProcessRunner;
import domain.config.*;
import work.QueryContext;
import wikidata.query.core.QueryFactory;
import domain.workbench.execution.GenerationRunState;
import domain.workbench.execution.ui.BuildProjectWorkflow;
import domain.workbench.execution.ui.GenerationWorkflows;
import domain.workbench.execution.ui.InstancesWindow;
import domain.workbench.execution.ui.RunSection;
import domain.workbench.execution.ui.SwingQueryRunner;
import domain.workbench.execution.ui.SwingQuerySession;
import domain.workbench.execution.ui.WorkflowLogWindow;
import domain.compile.rule.RuleNode;
import domain.compile.rule.RuleTreeCompiler;
import domain.compile.rule.RuleTreeConfig;
import domain.compile.rule.RuleTreeExplainer;
import domain.compile.rule.RuleTreeSerializer;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.prefs.Preferences;
import domain.workbench.ui.ExploreByExamplePanel;

public class ModelBuilderFrame extends JFrame {

    private static final Preferences PREFERENCES =
            Preferences.userNodeForPackage(ModelBuilderFrame.class);
    private static final String LAST_DOMAIN = "lastDomain";
    private static final String INSTANCE_CLASS_VISIBILITY = "instance-class-visibility";

    private final WikidataSparqlClient client;

    private final WikidataApiClient apiClient =
            new WikidataApiClient("QuizProject/1.0");

    private final dataset.DomainStorage storage = dataset.DomainStorage.inDefaultLocation();
    /** Survives switching domains: copying here and pasting there is the point. */
    private final ClassClipboard classClipboard = new ClassClipboard();
    private final GeneratedProjectModel projectModel =
            GeneratedProjectModel.constellationDemo();

    private final GenerationRunState runState = new GenerationRunState();
    private boolean closeWhenIdle;
    private boolean closingApplication;

    private final SingleRootClassModelPanel classModelPanel =
            new SingleRootClassModelPanel(projectModel);

    /** Every graph class's completed annotation set: the project's, like its instances. */
    private final GraphResults graphResults = new GraphResults(projectModel, storage);

    private final ModelSourceWorkbenchPanel sourceWorkbench =
            new ModelSourceWorkbenchPanel(projectModel, graphResults);

    private final InstancesWindow instancesWindow = new InstancesWindow(this, projectModel,
            runState, graphResults, this::showGraphFrontier, this::applyGraphResultFromInstances,
            this::modelFile, this::populationCreated);
    private final GenerationWorkflows generationWorkflows =
            new GenerationWorkflows(this::acceptGenerationRun, instancesWindow::show);

    private final RunSection runSection;

    private final JButton cancelButton =
            new JButton("Cancel");

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

    private final JButton openSavedRunButton =
            new JButton("Open saved query log…");

    private final JButton showExplorerButton =
            new JButton("Explorer tools");

    private final JButton showGraphButton =
            new JButton("Model graph");

    private final JButton showEntityKindsButton =
            new JButton("Class admissions");

    private final JButton showGuideButton =
            new JButton("Guide…");
    private final JLabel configurationLockLabel = new JLabel(
            "Configuration locked while a process is running — inspect or cancel to edit.");
    private boolean configurationLocked;

    private JFrame guideWindow;
    private ModelingGuidePanel guidePanel;

    private SelectionViewerPanel selectionsPanel;
    private JFrame entityKindsWindow;

    // Companion window holding the discovery tools (Explore/Sample/Discover/
    // WikiProject/Properties), like the instances + logs windows.
    private JFrame explorerWindow;

    // Graph view of the model (classes = nodes, reference fields = edges).
    private JFrame graphWindow;
    private final ModelGraphPanel graphPanel = new ModelGraphPanel();

    // Domain/Model are two ways to open one project. The picker owns their shared
    // selection state so the UI never appears to claim that two projects are open.
    private final ProjectPicker projectPicker = new ProjectPicker();
    private final JButton newDomainButton = new JButton("New domain");
    private final JButton renameDomainButton = new JButton("Rename domain");
    private final JButton deleteDomainButton = new JButton("Delete domain");
    /**
     * The model's fingerprint when it was last loaded or saved — what "unchanged" means.
     *
     * <p>Not a flag set by every edit path: there are a dozen of those and a new one is
     * added whenever a control is. DomainSave.signature already answers "is this the same
     * model" for the staleness guard, over a COPY, excluding declaration identities and
     * derived vocabulary values — so it says changed when a modeller changed something
     * and not when the model was merely re-read. Reusing it means the two questions
     * cannot drift apart, and a new editor is covered the day it is written.
     */
    private String savedSignature = "";

    /**
     * Where this project lives: the model file it was last loaded from or saved to.
     *
     * <p>Blank for a draft that has never been saved, which is the one state that has no
     * answer. Settled at exactly the moments {@link #savedSignature} is, because they are
     * the same moment — the project became identical to a file on disk.
     */
    private File openModelFile;
    private ProjectSaveGuard saveGuard;

    private final JButton loadProjectButton =
            new JButton("Load project");

    // "Load instances", because that is what it loads: the saved objects, into the
    // model that is already open. It was "Load saved", which named neither what is
    // loaded nor what it is loaded into, and asked for a folder the open domain
    // already knows.
    private final JButton loadSavedButton =
            new JButton("Load instances");

    private final JButton saveEverythingButton =
            new JButton("Save domain");

    private final SwingQuerySession querySession;
    private final WorkflowLogWindow logWindow;
    private final SwingProcessRunner processRunner;
    private final QueryFactory queryFactory;

    public ModelBuilderFrame(WikidataSparqlClient client) {
        super("Wikidata Viewable Model Builder");

        this.client = client;
        // The factory owns the datasource↔endpoint bindings; we take a context with every
        // datasource wired (WDQS default + DBpedia). client stays the WDQS primary.
        this.queryFactory = new QueryFactory(
                client, apiClient, "quiz-modelbuilder (ggyepesi@gmail.com)");
        QueryContext queryContext = queryFactory.newContext(
                java.nio.file.Path.of(aux.Constants.wikidataDataDirectory));
        this.querySession = new SwingQuerySession(queryContext);
        domain.workbench.ui.WikidataLabelLookup.install(querySession.runner());
        this.logWindow = querySession.logs();
        this.processRunner = new SwingProcessRunner(
                queryContext, logWindow, new SwingProcessInputHandler(this));
        this.runSection = new RunSection(new RunSection.Actions(
                () -> startGeneration(this::prepareClassGeneration),
                () -> startGeneration(this::prepareDomainGeneration), this::buildProject,
                () -> startGeneration(this::prepareRemap), () -> startGeneration(this::prepareEnrich),
                instancesWindow::show, this::showRunReport,
                () -> showStatementsWindow(querySession.runner())),
                this::storeGenerationDepth);
        this.classModelPanel.onModelChanged(this::modelChanged);
        this.selectionsPanel = new SelectionViewerPanel(projectModel, apiClient, client);
        this.selectionsPanel.selections(sourceWorkbench.selections());
        this.selectionsPanel.afterChange(selection -> {
            classModelPanel.refresh();
            if (selection != null) {
                classModelPanel.selectSelection(selection);
            } else {
                sourceWorkbench.showSelections(null);
            }
        });
        this.sourceWorkbench.selectionEditor(selectionsPanel);
        this.sourceWorkbench.domainStatus(() -> new DomainOverviewPanel.Status(
                modelFile().isFile(), snapshotFile().isFile(),
                runState.current() == null ? 0 : runState.current().size()));
        this.instancesWindow.onHiddenClassSectionsChanged(
                this::rememberInstanceClassVisibility);

        // Continue from the saved model (so edits like sharesBorderWith / the
        // Star class persist across restarts) instead of the hard-coded demo.
        loadSavedModelIfPresent();

        buildUi();
        wireActions();
        refreshProjectPicker();

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (closingApplication) {
                    dispose();
                    return;
                }
                requestApplicationClose();
            }
        });
        setSize(1750, 950);
        setLocationByPlatform(true);
    }

    /** Release the datasource clients owned by this frame. The primary WDQS client remains
     *  caller-owned, matching QueryFactory's ownership contract. */
    @Override
    public void dispose() {
        rememberCurrentDomain();
        closeProjectWindows();
        runState.replaceProject(null);
        refreshRunControls();
        queryFactory.close();
        sourceWorkbench.close();
        super.dispose();
    }

    /**
     * The one boundary for replacing the open domain/model. Generated instances,
     * graph annotations and every auxiliary window belong to that exact project;
     * none may survive into the replacement merely because it is held by this frame.
     */
    private void replaceOpenProject(
            GeneratedProjectModel replacement,
            domain.datasource.graph.GraphDiscoveryState replacementLedger) {
        closeProjectWindows();
        installOpenProject(replacement, replacementLedger);
    }

    /** Installs one configuration after its caller has settled the windows it replaces. */
    private void installOpenProject(
            GeneratedProjectModel replacement,
            domain.datasource.graph.GraphDiscoveryState replacementLedger) {
        sourceWorkbench.abandonEdits();
        markSaved(null, null);
        projectModel.copyContentsFrom(replacement);
        graphResultsFollowLoadedModel();
        runState.replaceProject(replacementLedger);
        refreshRunControls();
        instancesWindow.reset(rememberedInstanceClassVisibility());
    }

    /** Close every non-modal window whose contents describe the open project. */
    private void closeProjectWindows() {
        // Plan/running/results workflows are independent frames (so they can be
        // minimized) registered to this frame, plus any modeless dialog it owns. They
        // are intentionally not held as one field per action; the registration and
        // ownership are their common lifecycle handle.
        for (Window workflow : process.swing.workflow.SwingProcessWorkflow
                .openWorkflows(this)) closeWindow(workflow);
        for (Window owned : getOwnedWindows()) closeWindow(owned);
        closeProjectDataWindows();
        logWindow.resetForProjectSwitch();
    }

    /** Close views of generated/configured data while preserving the workflow whose
     * completed build is synchronizing this same project. */
    private void closeProjectDataWindows() {
        instancesWindow.close();
        closeWindow(statementsWindow);
        closeWindow(explorerWindow);
        closeWindow(entityKindsWindow);
        closeWindow(graphWindow);
        closeWindow(guideWindow);
    }

    private static void closeWindow(Window window) {
        if (window != null) window.dispose();
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
                        buildConfigPanel(),
                        0.34);

        main.setResizeWeight(0.34);

        add(main, BorderLayout.CENTER);

        SwingUtilities.invokeLater(() -> main.setDividerLocation(0.34));
    }

    // LEFT panel: the selected domain, its classes, and the run controls.
    private JPanel buildDomainClassPanel() {
        // Project selectors each get their own full-width row; keeping them beside the
        // action buttons made the Model selector collapse to an ellipsis.
        JPanel projectActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        projectActions.add(newDomainButton);
        projectActions.add(renameDomainButton);
        projectActions.add(deleteDomainButton);

        JPanel domainFiles = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        domainFiles.add(saveEverythingButton);
        domainFiles.add(loadProjectButton);
        loadSavedButton.setToolTipText(
                "Load the instances saved beside this project's model. "
                        + "Which folder that is follows from the open project.");
        domainFiles.add(loadSavedButton);

        JPanel domainSection = new JPanel();
        domainSection.setLayout(new BoxLayout(domainSection, BoxLayout.Y_AXIS));
        projectPicker.setAlignmentX(Component.LEFT_ALIGNMENT);
        projectActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        domainFiles.setAlignmentX(Component.LEFT_ALIGNMENT);
        domainSection.add(projectPicker);
        domainSection.add(projectActions);
        domainSection.add(domainFiles);
        // Generation, Explorer and sampling share the process/query runners. Their
        // activity controls stay visible for both project kinds, above the class tree.
        JPanel activity = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        activity.add(new JLabel("Activity:"));
        activity.add(cancelButton);
        activity.add(showQueryLogsButton);
        activity.add(openSavedRunButton);
        activity.setAlignmentX(Component.LEFT_ALIGNMENT);
        domainSection.add(activity);

        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(domainSection, BorderLayout.NORTH);
        panel.add(classModelPanel, BorderLayout.CENTER);
        panel.add(runSection, BorderLayout.SOUTH);
        return panel;
    }

    // Keep the editor's tools together below the configuration they work with.
    private JPanel buildConfigPanel() {
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        tools.add(showGeneratedSourceButton);
        tools.add(showRuleTreeButton);
        tools.add(showExplorerButton);
        tools.add(showGraphButton);
        showEntityKindsButton.setToolTipText(
                "Map Wikidata evidence such as P31 values to modeled entity kinds");
        tools.add(showEntityKindsButton);
        showGuideButton.setToolTipText("Guided build steps for the selected class: "
                                               + "what's done, what's next, the tool for it, and the hint");
        tools.add(showGuideButton);

        configurationLockLabel.setForeground(new Color(155, 90, 0));
        configurationLockLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 2));
        configurationLockLabel.setVisible(false);
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(configurationLockLabel, BorderLayout.NORTH);
        panel.add(sourceWorkbench, BorderLayout.CENTER);
        panel.add(tools, BorderLayout.SOUTH);
        return panel;
    }

    private void applyGraphResultFromInstances(GraphDiscoveryResultStore.Artifact result) {
        String action = "Apply accepted instances";
        File destination = snapshotBesideModel(openModelFile);
        String description = "Apply graph annotations \"" + result.type() + "\" to \""
                + result.projectName() + "\".\n\nThis will "
                + (result.populationOperation() == domain.datasource.graph.GraphDiscoveryConfiguration.PopulationOperation.ADD
                        ? "add accepted instances to " : "narrow ") + result.outputClass()
                + " to " + result.acceptedIdentities().size()
                + " accepted generated instance(s).\n\nSave "
                + (projectModel.isModel() ? "model" : "domain")
                + (destination == null
                        ? " will write them after the project is first saved."
                        : " will write the changed instances to\n"
                                + destination.getPath() + ".");
        if (process.swing.Dialogs.confirmPersistence(this, action, description)) {
            try {
                applyGraphResult(result);
            } catch (Exception refused) {
                JOptionPane.showMessageDialog(this, process.swing.Dialogs.wrapped(
                        refused.getMessage() == null ? refused.toString() : refused.getMessage()),
                        action, JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    /** Accepted identities of applied additive graph results not generated yet. */
    private java.util.Map<String, java.util.List<String>> pendingGraphPopulationAdditions() {
        return graphResults.pendingPopulationAcquisitions(
                runState.current() == null ? java.util.List.of() : runState.current().dynamicObjects());
    }

    private void populationCreated(PopulationSelection population) {
        projectModel.replaceSelection(population);
        modelChanged();
        classModelPanel.refresh();
        logWindow.info("Created population selection " + population.name() + " with "
                + population.instanceQids().size() + " " + population.className()
                + " QIDs. Use \"Save " + (projectModel.isModel() ? "model" : "domain")
                + "\" to persist it.");
    }

    // Lazily-created window hosting the discovery tools moved out of the main
    // frame, so the main window stays focused on domain + configuration.
    // Sample the selected class, then open the statement view on those instances.
    private void showStatementsWindow(domain.workbench.execution.ui.SwingQueryRunner queryRunner) {
        domain.compile.rule.RuleNode node = sourceWorkbench.temporaryRuleNodeForSelected();
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
                                && WikidataIds.isQid(row.get(0).toString())) {
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
                                                                                 domain.config.RuleDirection.ROOT_TO_ITEM));
            statementsPanel.onConfigureQualifier((pid, label) ->
                                                         sourceWorkbench.useQualifier(pid, label));
            statementsWindow = new JFrame("Statements — example-first (#91)");
            statementsWindow.add(statementsPanel);
            statementsWindow.setSize(780, 720);
            statementsWindow.setLocationRelativeTo(this);
        }
        showAndFocus(statementsWindow);
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
        showAndFocus(explorerWindow);
    }

    // Model-as-graph view: classes are nodes, entity-reference fields are edges.
    // Clicking a node selects that class in the workbench (the graph is a map +
    // selector, not an editor — the class/field panels stay where they are).
    // Browse the domain's Selections — named selections over the entity pool
    // (vocabularies / populations) that are referenced but never served. A
    // Selection's content is inspectable even though it isn't a product; declare a
    // vocabulary and see its members.
    private void showEntityKindsWindow() {
        if (entityKindsWindow == null) {
            entityKindsWindow = new JFrame("Class admission evidence");
            entityKindsWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            entityKindsWindow.setSize(700, 420);
            entityKindsWindow.setLocationByPlatform(true);
        }
        // Labels for the vocabulary picker come from the loaded pool — the same objects
        // the vocabulary was derived from, so a value always reads as what it is.
        // REACHABLE, not the roots: a vocabulary value (Q5 "human") is a nested field
        // value and never a top-level object, so a roots-only scan finds no labels at all.
        java.util.Map<String, String> labels = new java.util.concurrent.ConcurrentHashMap<>();
        // The reachable graph can be large. Warm the optional label cache away from
        // the EDT; until it is ready the picker safely displays the QID fallback.
        GenerationRun labelRun = runState.current();
        if (labelRun != null && labelRun.dynamicObjects() != null) {
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() {
                    for (WikidataDynamicObject o :
                            domain.runtime.WikidataObjectGraph.reachable(
                                    labelRun.dynamicObjects())) {
                        if (o != null && wikidata.WikidataIds.isQid(o.qid())
                                && o.getDisplayName() != null
                                && !o.getDisplayName().isBlank()) {
                            labels.putIfAbsent(o.qid(), o.getDisplayName());
                        }
                    }
                    return null;
                }
            }.execute();
        }
        entityKindsWindow.getContentPane().removeAll();
        entityKindsWindow.add(new EntityKindRulesPanel(projectModel, this::modelChanged,
                labels::get,
                (seed, onPicked) -> ExploreByExamplePanel.showPicker(
                        entityKindsWindow, querySession.runner(), seed, false,
                        (qid, label) -> onPicked.accept(qid))));
        entityKindsWindow.revalidate();
        entityKindsWindow.repaint();
        showAndFocus(entityKindsWindow);
        // Content built fresh is built enabled, and the lock was applied to the content
        // this just replaced. Re-applying it here is what keeps the window from being
        // the one editable surface during a run.
        if (configurationLocked) {
            EditableComponents.setEditable(entityKindsWindow.getContentPane(), false);
        }
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
            graphPanel.onFieldSelected((className, fieldName) -> {
                GeneratedClassModel c = classByName(className);
                if (c == null) {
                    return;
                }
                c.fields().stream()
                 .filter(f -> f != null && fieldName.equals(f.name()))
                 .findFirst()
                 .ifPresent(classModelPanel::selectField);
            });
            // Double-click a node → jump into live Explore of that class's
            // membership target (its Wikidata type/award), unifying the model
            // graph with the navigation canvas.
            graphPanel.onClassExplore(name -> {
                GeneratedClassModel c = classByName(name);
                if (c == null) {
                    return;
                }
                List<String> targets = c.effectiveMembership(projectModel).qids();
                String qid = targets.isEmpty() ? "" : targets.getFirst();
                if (!WikidataIds.isQid(qid)) {
                    logWindow.info("Class \"" + name + "\" has no membership target "
                                           + "QID to explore — set a Relation target first.");
                    return;
                }
                showExplorerWindow();
                sourceWorkbench.explorePanel().exploreQid(qid, name);
            });
        }
        graphPanel.setModel(projectModel);
        showAndFocus(graphWindow);
    }

    private void wireActions() {
        SwingQueryRunner queryRunner = querySession.runner();

        queryRunner.onRunningChanged(ignored -> {
            updateConfigurationLock();
            closeAfterCancellationIfIdle();
        });
        processRunner.onRunningChanged(ignored -> {
            updateConfigurationLock();
            closeAfterCancellationIfIdle();
        });

        queryRunner.registerCancelButton(cancelButton);
        processRunner.registerCancelButton(cancelButton);
        // The lock owns these, not registerRunButton: Remap and Enrich also require a
        // previous run, and two owners for one button's enabled state means whichever
        // runs last wins — which was only correct while the listeners happened to fire
        // in the right order.
        queryRunner.registerRunButton(showGeneratedSourceButton);
        queryRunner.cancelAction(client::cancelCurrentQuery);

        sourceWorkbench.setQueryRunner(queryRunner);
        sourceWorkbench.setProcessRunner(processRunner);
        sourceWorkbench.graphInstances(() -> runState.current() == null
                ? List.of() : runState.current().instances());
        sourceWorkbench.onGraphResult(this::applyGraphResult);
        sourceWorkbench.afterGraphResultApplied(instancesWindow::show);
        sourceWorkbench.log(logWindow::info);

        sourceWorkbench.afterChange(v -> {
            modelChanged();
            refreshProjectKindUi();
        });
        classModelPanel.onCopyClass(this::copyClassConfiguration);
        classModelPanel.onPasteClass(this::pasteClassConfiguration);
        classModelPanel.onImportClass(this::importClassConfiguration);
        classModelPanel.pasteAvailable(() -> classClipboard.canPasteInto(projectModel));

        sourceWorkbench.afterApplyField(f -> {
            modelChanged();
            classModelPanel.selectField(f);
            sourceWorkbench.edit(f);
        });

        sourceWorkbench.onGraphSelection(target -> {
            if (target instanceof GeneratedClassModel clazz) {
                classModelPanel.selectClass(clazz);
            } else if (target instanceof GeneratedFieldModel field) {
                classModelPanel.selectField(field);
            }
        });
        sourceWorkbench.onConfigurationNavigation(classModelPanel::selectConstruct);

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
            Object selected = classModelPanel.selectedUserObject();
            // The domain node is navigation context, not a configurable object. Keep
            // the current editor visible and let the domain row remain highlighted.
            if (SingleRootClassModelPanel.isConfigurable(selected)) {
                sourceWorkbench.changeSelection(selected);
            }
            // Per-class depth: show the newly-selected class's saved depth.
            syncDepthSpinnerToActiveClass();
            // Mirror the selection onto the graph (the two-way "connection").
            GeneratedClassModel sel = activeClass();
            graphPanel.setSelectedClass(sel == null ? "" : sel.className());
            if (guidePanel != null && guideWindow != null && guideWindow.isVisible()) {
                guidePanel.refresh();
            }
        });

        sourceWorkbench.onReloadField(this::forgetFetchedDeclaration);

        showExplorerButton.addActionListener(e -> showExplorerWindow());
        showGraphButton.addActionListener(e -> showGraphWindow());
        showEntityKindsButton.addActionListener(e -> showEntityKindsWindow());

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
        sourceWorkbench.onUseMembershipType(this::useMembershipType);

        CategorySeedPanel cat = sourceWorkbench.categoryPanel();
        cat.onUseAsSourceQid(this::useSourceQid);
        cat.onAddSeedQids(qids -> addSeedQids(qids, false));
        cat.onReplaceSeedQids(qids -> addSeedQids(qids, true));


        projectPicker.onOpen(this::switchToDomain);
        newDomainButton.addActionListener(e -> newProject());
        renameDomainButton.addActionListener(e -> renameDomain());
        deleteDomainButton.addActionListener(e -> deleteDomain());

        loadProjectButton.addActionListener(e -> loadProject());

        loadSavedButton.addActionListener(e -> loadSavedInstances());

        saveEverythingButton.addActionListener(e -> saveEverything());

        showGeneratedSourceButton.addActionListener(e ->
                                                            showGeneratedSource());

        showRuleTreeButton.addActionListener(e -> showRuleTree());
        showGuideButton.addActionListener(e -> showGuide());

        showQueryLogsButton.addActionListener(e -> querySession.showLogs(this));
        openSavedRunButton.addActionListener(e -> {
            domain.workbench.execution.ui.RunInspectorFrame inspector =
                    new domain.workbench.execution.ui.RunInspectorFrame(
                            projectDataDir().toPath());
            inspector.setLocationRelativeTo(this);
            inspector.setVisible(true);
            inspector.chooseAndOpen(this);
        });

        sourceWorkbench.edit(projectModel.rootClass());
        refreshProjectKindUi();
    }

    private GenerationWorkflows.Action prepareClassGeneration() {
        sourceWorkbench.applyEdits();
        GeneratedClassModel selected = activeClass();
        String problem = GenerationWorkflows.membershipProblem(projectModel, selected);
        if (problem != null) {
            warnNothingToGenerate(problem);
            return null;
        }
        return generationWorkflows.generateClass(projectModel, selected, runSection.depth(), runState.current());
    }

    private GenerationWorkflows.Action prepareRemap() {
        sourceWorkbench.applyEdits();
        if (runState.current() == null) {
            warnNothingToGenerate("Nothing to remap yet — run Generate domain (or instances) first.");
            return null;
        }
        return generationWorkflows.remap(projectModel, runState.current(), runState.graphLedger());
    }

    private GenerationWorkflows.Action prepareEnrich() {
        sourceWorkbench.applyEdits();
        if (runState.current() == null) {
            warnNothingToGenerate("Nothing to enrich yet — run Generate domain (or instances) first.");
            return null;
        }
        return generationWorkflows.enrich(projectModel, runState.current(), runState.graphLedger());
    }

    private GenerationWorkflows.Action prepareDomainGeneration() {
        String generationReason = runState.takeGenerationReason();
        sourceWorkbench.applyEdits();
        // Per-class depth is saved on each class; sync the active class first.
        storeGenerationDepth(runSection.depth());
        var graphAdditions = pendingGraphPopulationAdditions();
        boolean anyGeneratable = projectModel.classes().stream()
                .anyMatch(c -> GenerationWorkflows.membershipProblem(projectModel, c) == null) || !graphAdditions.isEmpty();
        if (!anyGeneratable) {
            warnNothingToGenerate(GenerationWorkflows.membershipProblem(projectModel, projectModel.rootClass()));
            return null;
        }
        return generationWorkflows.generateDomain(projectModel, runState.graphLedger(), graphAdditions,
                storage, generationReason, runState.current(), file -> GenerationWorkflows.chooseRecovery(this, file));
    }

    /** The host checks its own edits and busy state, then opens the shared action. */
    private void startGeneration(java.util.function.Supplier<GenerationWorkflows.Action> prepare) {
        if (processRunner.isRunning() || querySession.runner().isRunning()) return;
        try {
            var action = prepare.get();
            if (action == null) return;
            logWindow.registerPipeline(action.logTitle(), action.pipeline(), action.projectName(),
                    storage.snapshotFile(action.projectName()).getParentFile().toPath());
            process.swing.workflow.SwingProcessWorkflow.start(
                    this, processRunner, action, this::openPipelineReference);
        } catch (Exception ex) {
            reportGenerationError(ex);
        }
    }

    /**
     * Runs the project's saved build through the same coordinator as the command line.
     *
     * <p>It builds the SAVED project, so a build here and one from the command line are
     * the same build: with unsaved edits or unsaved instances it says to save first,
     * rather than building something other than what is open. The plan names the steps
     * and the files; each step's state and reasons are in the log and the results, because
     * reading the snapshot to judge them takes seconds and the plan is shown at once.
     * Completion loads what the build saved, by the ordinary Load instances.
     */
    private void buildProject() {
        if (processRunner.isRunning() || querySession.runner().isRunning()) return;
        sourceWorkbench.applyEdits();
        java.util.List<String> unsaved = new java.util.ArrayList<>();
        if (openModelFile == null) unsaved.add("the project has never been saved");
        else if (hasUnsavedChanges()) unsaved.add("the configuration has unsaved changes");
        if (runState.hasUnsavedInstances()) {
            unsaved.add("the instances or graph decisions are not saved");
        }
        String kind = projectModel.isModel() ? "model" : "domain";
        if (!unsaved.isEmpty()) {
            JOptionPane.showMessageDialog(this, process.swing.Dialogs.wrapped(
                    "Build runs the saved project, the same build as the command line, and "
                            + String.join(" and ", unsaved) + ". Use \"Save " + kind
                            + "\" first, then build."),
                    "Build project", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        File modelFile = modelFile();
        GeneratedProjectModel saved;
        ProjectSaveGuard buildGuard;
        try {
            logWindow.info("Build: reading model " + modelFile.getPath() + "\n");
            buildGuard = guardForModel(modelFile);
            saved = new GeneratedProjectModelStore().load(modelFile);
            buildGuard.check();
        } catch (Exception unreadable) {
            reportGenerationError(unreadable);
            return;
        }
        if (saved.buildOperations().isEmpty()) {
            String warnings = BuildProjectWorkflow.missingBuildWarnings(saved);
            JOptionPane.showMessageDialog(this, process.swing.Dialogs.wrapped("\"" + saved.name()
                            + "\" has no build. Add its steps under Build in the project "
                            + "overview, then save the " + kind + "."
                            + (warnings.isBlank() ? "" : "\n\n" + warnings)),
                    "Build project", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        File builtSnapshot = storage.snapshotFile(saved.name());
        var action = BuildProjectWorkflow.action(
                saved, modelFile, storage, buildGuard,
                "Load " + modelFile.getName() + " and " + builtSnapshot.getName(),
                say -> {
                    BuiltProject read = readBuiltProject(modelFile, say);
                    return new BuildProjectWorkflow.Loaded<>(read,
                            read.loaded().run().dynamicObjects().size() + " saved object(s) read; mapped "
                                    + read.loaded().run().instances().size() + " instance(s)");
                }, read -> synchronizeCompletedBuild(modelFile, read),
                "The open project has been refreshed. Use Show instances to inspect it.");
        logWindow.registerPipeline("Build project — " + saved.name(), action.pipeline(),
                saved.name(), storage.snapshotFile(saved.name()).getParentFile().toPath());
        process.swing.workflow.SwingProcessWorkflow.start(this, processRunner, action);
    }

    /** A completed build's saved files, read in the build's worker. */
    private record BuiltProject(GeneratedProjectModel model,
                                domain.datasource.graph.GraphDiscoveryState ledger, File snapshot,
                                domain.build.ProjectLoad.Loaded loaded,
                                ProjectSaveGuard guard) { }

    /**
     * Reads the configuration and snapshot a build just saved. It touches no Swing state:
     * it runs as the build's last phase, off the event thread, so a large snapshot is
     * read while the window names the files being read.
     */
    private BuiltProject readBuiltProject(File modelFile,
                                          java.util.function.Consumer<String> say)
            throws Exception {
        say.accept("Reading built model " + modelFile.getPath());
        ProjectSaveGuard guard = guardForModel(modelFile);
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(modelFile);
        File snapshot = snapshotBesideModel(modelFile);
        if (snapshot == null || !snapshot.isFile()) {
            throw new java.io.IOException("The completed build did not leave its snapshot at "
                    + (snapshot == null ? "the configured project path" : snapshot.getPath()));
        }
        say.accept("Reading built snapshot " + snapshot.getPath());
        domain.datasource.graph.GraphDiscoveryState ledger = graphDiscoveryBeside(modelFile);
        domain.build.ProjectLoad.Loaded loaded =
                domain.build.ProjectLoad.load(model, storage, snapshot, say);
        guard.check();
        return new BuiltProject(model, ledger, snapshot, loaded, guard);
    }

    /**
     * A build writes the project while it runs. Install that exact saved configuration and
     * snapshot into the editor before its result page appears, so a later Save cannot
     * overwrite a population the build just published with the editor's older copy. The
     * files were already read by {@link #readBuiltProject}; this only installs them.
     */
    private void synchronizeCompletedBuild(File modelFile, BuiltProject built) {
        GeneratedClassModel selected = activeClass();
        String selectedId = selected == null ? "" : selected.declarationId();
        String selectedName = selected == null ? "" : selected.className();

        closeProjectDataWindows();
        installOpenProject(built.model(), built.ledger());
        modelChanged();
        GeneratedClassModel restored = projectModel.resolveClass(selectedId, selectedName);
        classModelPanel.selectClass(restored == null ? projectModel.rootClass() : restored);
        syncDepthSpinnerToActiveClass();
        refreshProjectPicker();
        refreshProjectKindUi();
        markSaved(modelFile, built.guard());
        // A successful build has no Apply/Accept step: it already saved and installed
        // its output. Completion synchronizes only; the result page names the explicit
        // Show instances action instead of opening another window on the reader's behalf.
        installSavedInstances(built.snapshot(), built.loaded(), false);
        logWindow.info("Synchronized the completed build from " + modelFile.getPath()
                + "; the open configuration and instances now match its saved files.\n");
    }

    /** Turns the pipeline explanation into a map back to the configuration that
     * produced it. Property references live in Explore; classes and fields live in
     * the model tree. Unknown/stale references remain harmless in saved runs. */
    private void openPipelineReference(process.PhaseExplanation.ModelReference reference) {
        if (reference == null) return;
        switch (reference.kind()) {
            case CLASS -> {
                GeneratedClassModel clazz = classByName(reference.name());
                if (clazz != null) classModelPanel.selectClass(clazz);
            }
            case FIELD -> {
                GeneratedClassModel owner = classByName(reference.owner());
                if (owner == null) return;
                owner.fields().stream()
                        .filter(field -> reference.name().equals(field.name()))
                        .findFirst()
                        .ifPresent(classModelPanel::selectField);
            }
            case PROPERTY -> {
                showExplorerWindow();
                sourceWorkbench.showProperties();
            }
            case KIND_RULE -> showEntityKindsWindow();
            case ROLE, PHASE -> { /* Informational until those editors expose navigation. */ }
        }
    }

    private void acceptGenerationRun(GenerationRun run) {
        acceptGenerationRun(run, false);
    }

    /**
     * Applies a graph result through the same operation a headless build runs, or refuses
     * naming the Review entries it is waiting for. Refusing by throwing keeps the results
     * dialog open, where those entries can be decided.
     */
    private void applyGraphResult(GraphDiscoveryResultStore.Artifact artifact) throws Exception {
        domain.build.GraphApplication.Outcome outcome =
                domain.build.GraphApplication.apply(
                        artifact, projectModel, runState.current());
        if (outcome instanceof domain.build.GraphApplication.AwaitingDecision waiting) {
            logWindow.info(waiting.message() + "\n");
            throw new IllegalStateException(waiting.message());
        }
        var applied = (domain.build.GraphApplication.Applied) outcome;
        graphResults.markApplied(projectModel.findClass(artifact.type()), applied.result());
        acceptGenerationRun(applied.run());
        logWindow.info(applied.message(projectModel.isModel()) + "\n");
    }

    private void acceptGenerationRun(GenerationRun run, boolean alreadySaved) {
        SwingUtilities.invokeLater(() -> {
            runState.accept(run, alreadySaved);
            refreshRunControls();
            sourceWorkbench.graphInstances(() -> runState.current() == null
                    ? List.of() : runState.current().instances());

            instancesWindow.instanceDomain(null);
            instancesWindow.refreshRun();
            if (run != null) {
                inspectCuration(run);
                // Generation runs on a COPY of the model, so a descriptive vocabulary
                // built from the loaded data (e.g. NomineeType, WorkGenre) lands on
                // that copy and is otherwise lost. Fold the built values back into the
                // live model so this session shows them. Descriptive vocabularies are
                // REFRESHED, not filled-if-empty — they are derived, so a stale value
                // must not linger; an authored constraint vocabulary (OscarCategories)
                // is not a descriptive target and is never touched.
                int filledVocabs = mergeBuiltVocabularies(run.modelSnapshot());
                // These instances were just produced FROM this configuration, so the two
                // agree by construction — including the vocabularies the run folded back
                // into the model, which are derived from the very data being installed.
                String saveAction = projectModel.isModel() ? "Save model" : "Save domain";
                if (filledVocabs > 0) {
                    modelChanged();
                    logWindow.info(filledVocabs + " vocabulary(ies) filled from the "
                                           + "generated data — use \"" + saveAction
                                           + "\" to persist them.");
                }
                // Do NOT auto-save: generating used to silently overwrite the
                // project snapshot (a greekmyth run clobbered constellations).
                // The run is held in memory + shown here; persisting is explicit
                // and confirmed via "Save domain".
                logWindow.info("Generated " + run.size()
                                       + " objects (in memory — use \"" + saveAction
                                       + "\" to persist "
                                       + "to " + snapshotFile().getName() + ").");
                reportNameCollisions();
                // What the declared expectations found. Silent when they all held, so a
                // clean run says nothing and a gap is the only thing that speaks up.
                // The results window is gone the moment it is accepted, taking every
                // rule bucket with it. The run still HOLDS what it did — so say it here,
                // where it stays readable and is saved with the run log.
                logWindow.info(domain.build.RunAudits.report(run));
            }
        });
    }

    private void inspectCuration(GenerationRun run) {
        File snapshot = snapshotBesideModel(openModelFile);
        if (snapshot == null) snapshot = snapshotFile();
        if (!DomainCatalog.hasCuration(snapshot)) return;
        File source = snapshot;
        new SwingWorker<DomainModel, Void>() {
            @Override protected DomainModel doInBackground() {
                return DomainCatalog.inspectRun(source, run.modelSnapshot(), run);
            }

            @Override protected void done() {
                if (runState.current() != run) return;
                try {
                    instancesWindow.instanceDomain(get());
                    instancesWindow.refreshRun();
                } catch (Exception failure) {
                    logWindow.info("Could not apply curation beside " + source.getPath()
                            + ": " + failure.getMessage() + "\n");
                }
            }
        }.execute();
    }

    private void showGraphFrontier() {
        if (runState.current() == null || processRunner.isRunning()
                || querySession.runner().isRunning()) return;
        try {
            sourceWorkbench.applyEdits();
        } catch (Exception failure) {
            reportGenerationError(failure);
            return;
        }
        GraphFrontierWorkflowAction action = new GraphFrontierWorkflowAction(
                currentRunWorkflowSubject(), runState.visibleGraphLedger(), instancesWindow.reviewableEdges(),
                runState.current().dynamicObjects(),
                this::applyGraphFrontier, runSection::requestDomainGeneration);
        process.swing.workflow.SwingProcessWorkflow.start(this, processRunner, action);
    }

    private void applyGraphFrontier(
            java.util.List<GraphFrontierWorkflowAction.Decision> decisions) {
        if (decisions.isEmpty()) return;
        int queued = 0;
        for (GraphFrontierWorkflowAction.Decision decision : decisions) {
            if (WikidataIds.isQid(decision.qid())) {
                // Resolved against the edges offered for review, not the persisted
                // patterns: a field edge is derived from the model and is not in there,
                // so an id lookup could only ever find a statement pattern.
                domain.datasource.graph.GraphEdgeDefinition edge = instancesWindow.reviewableEdges().stream()
                        .filter(candidate -> candidate.id().equals(decision.patternId()))
                        .findFirst().orElse(null);
                if (runState.queueExpansion(edge, domain.datasource.EntityRef.wikidata(decision.qid()))) {
                    queued++;
                }
            }
        }
        instancesWindow.frontierChanged();
        runState.explainNextGeneration("Expand " + decisions.size() + " graph-frontier node(s)");
        logWindow.info("Queued " + queued
                + " graph-frontier node(s) in the snapshot ledger"
                + (queued == decisions.size() ? "" : " ("
                + (decisions.size() - queued) + " already queued)")
                + "; opening Generate domain preview.");
    }

    private void replaceGenerationRun(GenerationRun next) {
        runState.replace(next);
        refreshRunControls();
    }

    private void refreshRunControls() {
        runSection.updateRunReport(runState.current());
        sourceWorkbench.refreshDomainOverview();
    }

    private String currentRunWorkflowSubject() {
        return runState.workflowSubject();
    }

    private void showRunReport() {
        GenerationWorkflows.showReport(this, processRunner, projectModel.name(),
                runState.workflowSubject(), runState.current());
    }

    /** Fold vocabularies BUILT during generation (from a referenced field's loaded
     *  values) back into the live model, so this session shows them. Descriptive vocabs
     *  (NomineeType, WorkGenre) are REFRESHED — they are derived, so a stale value must
     *  not linger — and created locally if generation invented them. An authored
     *  constraint vocab (OscarCategories) is untouched because it is not a descriptive
     *  target, not because anything checks whether it already has values.
     *  @return how many vocabularies were refreshed/created. */
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
                domain.compile.transform.DescriptiveVocabularyBuild.targets(into);
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
    private void reportNameCollisions() {
        var byClass = runState.refreshNameCollisions();
        instancesWindow.nameCollisionsChanged();
        if (byClass.isEmpty()) return;
        var ambiguity = domain.build.NameCollisions.Meaning.ENTITY_AMBIGUITY;
        var statement = domain.build.NameCollisions.Meaning.STATEMENT_REPETITION;
        var owned = domain.build.NameCollisions.Meaning.OWNED_REPETITION;
        java.util.List<domain.build.NameCollisions.ClassCollisions>
                entityClasses = domain.build.NameCollisions.classes(
                        byClass, ambiguity);
        // Structured, collapsible entry — one row per colliding name (name ×count),
        // biggest first — instead of one blob listing every QID (which buried the
        // rest of the log). The QIDs themselves are inspectable, clickable, via the
        // "Name collisions" button; the row detail keeps them collapsed.
        // One row per class, then that class's worst labels, under one entry per
        // MEANING. Three entries rather than one because the counts are of different
        // orders and answer different questions: on Oscars, 2499 Nominations repeat a
        // nominee's name and 145 entities are genuinely ambiguous, and in a single list
        // the first buries the second. Each is still reported — a derived label that
        // repeats says something about the rule that derived it.
        reportSharedLabels(entityClasses,
                "ambiguous entity name(s)", "distinct datasource entities",
                "entities", true);
        reportSharedLabels(domain.build.NameCollisions.classes(byClass, statement),
                "repeated statement label(s)",
                "statement records repeating a label their display-name rule takes "
                        + "from a participant",
                "statement records", false);
        reportSharedLabels(domain.build.NameCollisions.classes(byClass, owned),
                "repeated owned-part label(s)",
                "owned parts whose display name is inherited from their owner",
                "owned parts", false);
    }

    private void reportSharedLabels(
            java.util.List<domain.build.NameCollisions.ClassCollisions> classes,
            String summaryNoun, String explanation, String instanceNoun,
            boolean inspectableAsCollision) {
        if (classes == null || classes.isEmpty()) return;
        int labels = domain.build.NameCollisions.flatten(classes).size();
        int instances = domain.build.NameCollisions.instanceCount(classes);
        java.util.List<domain.workbench.execution.ui.WorkflowLogWindow.Row> rows =
                new java.util.ArrayList<>();
        int perClass = 8;
        for (var clazz : classes) {
            rows.add(new domain.workbench.execution.ui.WorkflowLogWindow.Row(
                    clazz.className(), clazz.size() + " shared label(s) — most repeated "
                            + clazz.worst() + " times", ""));
            int shown = 0;
            for (var shared : clazz.collisions()) {
                if (shown++ >= perClass) {
                    rows.add(new domain.workbench.execution.ui.WorkflowLogWindow.Row(
                            "    … and " + (clazz.size() - perClass) + " more in "
                                    + clazz.className(),
                            inspectableAsCollision
                                    ? "open \"Name collisions\" to see all" : "", ""));
                    break;
                }
                rows.add(new domain.workbench.execution.ui.WorkflowLogWindow.Row(
                        "    " + shared.name(),
                        shared.size() + " " + instanceNoun, ""));
            }
        }
        logWindow.structuredEntry(
                labels + " " + summaryNoun + " across " + classes.size()
                        + " class(es) — " + instances + " instances involved",
                explanation + (inspectableAsCollision
                        ? "; open \"Name collisions (" + labels + ")\" to inspect." : "."),
                rows);
    }

    /** Information buttons are idempotent: reveal and focus their retained window
     * instead of silently creating another copy. Also restores a minimized frame. */
    private static void showAndFocus(JFrame window) {
        objectview.utils.swing.SwingWindowActivation.showAndFocus(window);
    }

    // Returns a human-readable reason the class can't be populated, or null if it
    // can. A class is generatable with a membership target (sourceQid), extra type
    // QIDs, or an explicit seed-QID set — same rule as GenerateDomainQuery. The
    // message is specific: a set relation with a blank target is the common trap
    // (e.g. P1411 "nominated for" with no award to point at).
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
    /**
     * The model changed — re-render EVERY view of it. The class tree is only one:
     * the model graph and the build guide draw from the same model and silently
     * went stale whenever a caller remembered the tree and forgot them, which is
     * why a domain switch or a generation run left them describing the previous
     * model. One call, so a new mutation site cannot refresh some views and not
     * others. Selection changes are NOT model changes and stay separate.
     */
    /** Drops one declaration from the run's fetch record, so the next Enrich loads that
     *  field again. The record is what keeps Enrich cheap — a field already fetched is
     *  skipped even where Wikidata had no answer — so refreshing VALUES is an explicit
     *  act, per field, rather than a flag on the whole run. */
    private void forgetFetchedDeclaration(String declarationKey) {
        if (runState.current() == null || declarationKey == null || declarationKey.isBlank()) {
            return;
        }
        List<domain.runtime.LoadedDeclaration> kept =
                runState.current().loadedDeclarations().stream()
                        .filter(d -> !declarationKey.equals(d.key()))
                        .toList();
        if (kept.size() == runState.current().loadedDeclarations().size()) {
            logWindow.info(declarationKey + " has not been fetched in this run — "
                    + "the next Enrich loads it anyway.");
            return;
        }
        // Forgetting a declaration changes what the NEXT enrich will fetch. It does
        // not un-run the rules this run already ran, so their audits carry over —
        // rebuilding without them would quietly report that nothing had been evaluated.
        replaceGenerationRun(new GenerationRun(
                runState.current().modelSnapshot(), runState.current().depth(), runState.current().plan(),
                runState.current().dynamicObjects(), runState.current().runtime(), runState.current().instances(),
                runState.current().remapState(), kept, runState.current().quality(), runState.current().fieldCoverage(),
                runState.current().selfReferenceAudit(), runState.current().ownedCompositionAudit(),
                runState.current().kindClassificationAudit(), runState.current().projectionAudit())
                .producedLike(runState.current()));
        logWindow.info("Will re-fetch " + declarationKey + " on the next Enrich.");
    }

    /**
     * Copy takes the class in front of the reader. There is nothing to choose: the source
     * is where they are, which is what makes paste — not copy — the operation that asks.
     */
    private void copyClassConfiguration() {
        sourceWorkbench.applyEdits();
        GeneratedClassModel selected = classModelPanel.selectedClassOrRoot();
        if (selected == null) return;
        if (selected.isImported()) {
            JOptionPane.showMessageDialog(this,
                    selected.className() + " is imported from " + selected.importedFrom()
                            + ".\n\nCopy it from that model, so the copy is of the class "
                            + "as its owner defines it.",
                    "Copy class", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        classClipboard.copy(projectModel, selected.className());
        classModelPanel.refresh();
        JOptionPane.showMessageDialog(this,
                selected.className() + " copied from " + projectModel.name()
                        + ".\n\nOpen the domain or model to put it in, then Paste class.",
                "Copy class", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Paste puts the copied class where the reader now is. */
    private void pasteClassConfiguration() {
        if (!classClipboard.canPasteInto(projectModel)) {
            JOptionPane.showMessageDialog(this,
                    classClipboard.refusalFor(projectModel),
                    "Paste class", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        bringIn(classClipboard.snapshot(), classClipboard.className(),
                classClipboard.sourceName(), ClassImportPlan.Ownership.COPY);
    }

    private void importClassConfiguration() {
        boolean importing = true;
        try {
            sourceWorkbench.applyEdits();
            java.util.List<String> sources =
                    storage.importSourcesFor(projectModel.name());
            if (sources.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "No saved model is available to import from.\n\n"
                                + "An imported class stays owned by the model it comes "
                                + "from. Create a model and put the classes there first "
                                + "— copy and paste them in from a domain.",
                        "Import class", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            java.util.LinkedHashMap<String, String> labelled =
                    new java.util.LinkedHashMap<>();
            for (String name : sources) labelled.put(name + "  (model)", name);
            Object[] sourceChoices = labelled.keySet().toArray();
            String chosen = (String) JOptionPane.showInputDialog(
                    this, "Import a class from:", "Import class",
                    JOptionPane.PLAIN_MESSAGE, null, sourceChoices, sourceChoices[0]);
            if (chosen == null) return;
            String domain = labelled.get(chosen);
            if (domain == null) return;

            File sourceFile = storage.modelFileOf(domain);
            GeneratedProjectModel source = new GeneratedProjectModelStore().load(sourceFile);
            java.util.List<String> classNames = source.classes().stream()
                    .map(GeneratedClassModel::className).toList();
            String className = (String) JOptionPane.showInputDialog(
                    this, "Class:", "Import from " + domain,
                    JOptionPane.PLAIN_MESSAGE, null, classNames.toArray(),
                    classNames.getFirst());
            if (className == null) return;
            bringIn(source, className, domain, ClassImportPlan.Ownership.IMPORT);
        } catch (Exception failure) {
            JOptionPane.showMessageDialog(this,
                    failure.getMessage() == null ? failure.toString() : failure.getMessage(),
                    "Could not import class", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Brings one class from {@code source} into this project — the half paste and import
     * share. What differs is where the source came from and whether the result is this
     * project's own, and both are settled before this is called.
     */
    private void bringIn(GeneratedProjectModel source, String className,
            String domain, ClassImportPlan.Ownership ownership) {
        boolean importing = ownership == ClassImportPlan.Ownership.IMPORT;
        try {
            sourceWorkbench.applyEdits();
            ClassImportPlan plan = ClassImportPlan.of(source, projectModel, className);
            DefaultListModel<String> dependencyModel = new DefaultListModel<>();
            plan.dependencyClassNames().forEach(dependencyModel::addElement);
            JList<String> dependencies = new JList<>(dependencyModel);
            dependencies.setVisibleRowCount(Math.min(8,
                    Math.max(2, dependencyModel.getSize())));
            dependencies.setSelectionMode(
                    ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            if (!dependencyModel.isEmpty()) {
                dependencies.setSelectionInterval(0, dependencyModel.size() - 1);
                dependencies.setEnabled(!importing);
            }

            JTextArea preview = new JTextArea(8, 52);
            preview.setEditable(false);
            preview.setLineWrap(true);
            preview.setWrapStyleWord(true);
            preview.setText((importing ? "Import " : "Paste ") + className + " from "
                    + domain + " into " + projectModel.name() + ".\n\n"
                    + (importing
                            ? "The class stays owned by " + domain
                                    + ". It is shown and used here, and edited there.\n\n"
                            : "The copy belongs to " + projectModel.name()
                                    + " and is edited here like any other class.\n\n")
                    + "Class configuration: identity, display name, inheritance, "
                    + "fields and source bindings.\n"
                    + "Supporting declarations: " + plan.selections().size()
                    + " selection(s), " + plan.kindRules().size()
                    + " entity-kind rule(s).\n"
                    + "\nGenerated instances, observed vocabulary values, counts "
                    + "and curation are not copied.");

            JPanel choices = new JPanel();
            choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
            // First, and outside the preview. Inside it the warning sat below a screenful
            // of description in a scrolling area, so the one question that loses work was
            // the one thing a reader had to go looking for.
            JLabel warning = new JLabel();
            warning.setForeground(new java.awt.Color(150, 40, 40));
            warning.setBorder(BorderFactory.createEmptyBorder(2, 2, 8, 2));
            warning.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            choices.add(warning);
            Runnable updateConflicts = () -> {
                java.util.Set<String> conflicts = plan.conflicts(ownership,
                        new java.util.LinkedHashSet<>(dependencies.getSelectedValuesList()));
                warning.setVisible(!conflicts.isEmpty());
                warning.setText("<html><b>"
                        + (importing
                                ? "Already here, so this import is refused: "
                                : "Already here and REPLACED, losing what is here now: ")
                        + String.join(", ", conflicts)
                        + "</b><br>"
                        + (importing
                                ? "Rename or remove them first."
                                : "Cancel and rename it first if you want to keep both.")
                        + "</html>");
            };
            updateConflicts.run();
            dependencies.addListSelectionListener(event -> {
                if (!event.getValueIsAdjusting()) updateConflicts.run();
            });
            JScrollPane previewScroll = new JScrollPane(preview);
            previewScroll.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            choices.add(previewScroll);
            if (!dependencyModel.isEmpty()) {
                choices.add(Box.createVerticalStrut(8));
                choices.add(new JLabel(importing
                        ? "Required dependent classes:"
                        : "Dependent classes (selected = copy):"));
                choices.add(new JScrollPane(dependencies));
            }
            // Replace or cancel, asked once. A second control offering to keep the
            // existing declaration asked the same question a different way, and the
            // answer it added is better reached by renaming what is here first.
            int accepted = JOptionPane.showConfirmDialog(this, choices,
                    importing ? "Import class" : "Paste class",
                    JOptionPane.OK_CANCEL_OPTION,
                    !warning.isVisible()
                            ? JOptionPane.PLAIN_MESSAGE : JOptionPane.WARNING_MESSAGE);
            if (accepted != JOptionPane.OK_OPTION) return;

            java.util.LinkedHashSet<String> selected =
                    new java.util.LinkedHashSet<>(dependencies.getSelectedValuesList());
            selected.add(className);
            java.util.List<GeneratedClassModel> imported =
                    plan.apply(selected, ownership);
            modelChanged();
            GeneratedClassModel copied = projectModel.findClass(className);
            if (copied != null) classModelPanel.selectClass(copied);
            sourceWorkbench.edit(copied);
            JOptionPane.showMessageDialog(this,
                    imported.isEmpty()
                            ? "The selected target declarations were reused."
                            : (importing ? "Imported " : "Pasted ") + imported.size()
                                    + " class configuration(s): "
                                    + imported.stream().map(GeneratedClassModel::className)
                                            .collect(java.util.stream.Collectors.joining(", ")),
                    importing ? "Import class" : "Paste class",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception failure) {
            JOptionPane.showMessageDialog(this,
                    failure.getMessage() == null ? failure.toString() : failure.getMessage(),
                    importing ? "Could not import class" : "Could not paste class",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void modelChanged() {
        restampRenamedInstances();
        classModelPanel.refresh();
        sourceWorkbench.refreshDomainOverview();
        instancesWindow.refresh();
        if (graphWindow != null && graphWindow.isVisible()) {
            graphPanel.setModel(projectModel);
        }
        if (guidePanel != null && guideWindow != null && guideWindow.isVisible()) {
            guidePanel.refresh();
        }
    }

    /**
     * Brings the held instances to the model's current class names. A rename changes
     * the model only; the instances were stamped against the run's model, and showing
     * or saving them under names the model no longer has made a renamed class look
     * removed. Called wherever the instances are projected: on every model change and
     * before Save.
     */
    private void restampRenamedInstances() {
        if (runState.current() == null) return;
        try {
            GenerationRun renamed =
                    domain.build.GenerationRuns.renamedTo(runState.current(), projectModel);
            if (renamed != runState.current()) replaceGenerationRun(renamed);
        } catch (Exception error) {
            reportGenerationError(error);
        }
    }

    /** A model loaded in place brings its own saved graph results and none of the
     *  previous project's: the one restore path, before any instances are loaded. */
    private void graphResultsFollowLoadedModel() {
        graphResults.clear();
        graphResults.restore(java.util.List.of(), this::logInfo);
    }

    private void logInfo(String message) {
        if (logWindow != null) logWindow.info(message + "\n");
    }

    private void updateConfigurationLock() {
        setConfigurationLocked(processRunner.isRunning() || querySession.runner().isRunning());
    }

    private void setConfigurationLocked(boolean locked) {
        if (configurationLocked == locked) return;
        configurationLocked = locked;
        configurationLockLabel.setVisible(locked);

        projectPicker.setEditingEnabled(!locked);
        newDomainButton.setEnabled(!locked);
        renameDomainButton.setEnabled(!locked);
        deleteDomainButton.setEnabled(!locked);
        loadProjectButton.setEnabled(!locked);
        loadSavedButton.setEnabled(!locked);
        saveEverythingButton.setEnabled(!locked);
        boolean editable = !locked;
        classModelPanel.setEditingEnabled(editable);
        sourceWorkbench.setEditingEnabled(editable);
        showEntityKindsButton.setEnabled(!locked);
        showGuideButton.setEnabled(!locked);

        runSection.setRunLocked(locked, runState.current() != null);
        // Read-only, not switched off: a disabled JFrame ignores every event including
        // its own close button, so a window opened before a 25-minute generation could
        // neither be used nor dismissed.
        if (entityKindsWindow != null) {
            EditableComponents.setEditable(entityKindsWindow.getContentPane(), !locked);
        }
        if (guideWindow != null) {
            EditableComponents.setEditable(guideWindow.getContentPane(), !locked);
        }

        revalidate();
        repaint();
    }

    private void refreshProjectKindUi() {
        boolean model = projectModel.isModel();
        newDomainButton.setText("New…");
        renameDomainButton.setText(model ? "Rename model" : "Rename domain");
        deleteDomainButton.setText(model ? "Delete model" : "Delete domain");
        saveEverythingButton.setText(model ? "Save model" : "Save domain");
        saveEverythingButton.setToolTipText(model
                ? "Save this model's reusable configuration and generated instances together."
                : "Save this domain's configuration and generated instances together.");
        // Asked once: for a MODEL this validates the whole project, and this method runs
        // on every edit through sourceWorkbench.afterChange.
        boolean runnable = projectModel.supportsExecution();
        runSection.showProject(model, runnable);
        loadSavedButton.setVisible(runnable);
        boolean editable = !configurationLocked;
        renameDomainButton.setEnabled(editable);
        classModelPanel.setEditingEnabled(editable);
        sourceWorkbench.setEditingEnabled(editable);
        revalidate();
        repaint();
    }

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
        showAndFocus(guideWindow);
    }

    // The context follows the exact tree selection: domain, class or field.
    private domain.workbench.advisor.DecisionContext guideContext() {
        Object selected = classModelPanel.selectedUserObject();
        GeneratedFieldModel field = selected instanceof GeneratedFieldModel f ? f : null;
        GeneratedClassModel active = selected instanceof GeneratedClassModel c
                ? c
                : field == null ? null : activeClass();
        return new domain.workbench.advisor.DecisionContext(
                projectModel, active, field,
                new domain.compile.transform.TransformConfig());
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
                                                   process.swing.Dialogs.owner(this), body,
                                                   "Generation failed",
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

    // Repopulate the separate domain/model selectors from storage plus the current
    // (possibly unsaved) project. ProjectPicker owns the rebuild guard and makes the
    // current project the only selected entry across the two lists.
    private void refreshProjectPicker() {
        java.util.LinkedHashSet<String> domains =
                new java.util.LinkedHashSet<>(storage.domainKindNames());
        java.util.LinkedHashSet<String> models =
                new java.util.LinkedHashSet<>(storage.modelKindNames());
        (projectModel.isModel() ? models : domains).add(projectModel.name());
        projectPicker.showProjects(
                domains, models, projectModel.name(), projectModel.isModel());
        logWindow.saveContext(projectModel.name(), projectDataDir().toPath());
    }

    private domain.persistence.DatasetRegistry.Dataset findDataset(String name) {
        return storage.find(name);
    }

    private File domainModelFile(String name) {
        return storage.modelFileOf(name);
    }

    /**
     * Whether this project differs from the file it came from.
     *
     * <p>An unknown signature is not a claim that nothing changed: a model that cannot be
     * compiled has none, and a draft has no file to differ from. Both answer "yes, ask" —
     * the safe direction, since the cost of asking is a dialog and the cost of not asking
     * is lost work.
     */
    private boolean hasUnsavedChanges() {
        String saved = savedSignature;
        String now = modelSignature(projectModel);
        return saved.isBlank() || now.isBlank() || !now.equals(saved);
    }


    /** Closing is an explicit decision whenever it would stop work or lose memory-only data. */
    private void requestApplicationClose() {
        boolean running = processRunner.isRunning() || querySession.runner().isRunning();
        if (!running) {
            try {
                // Text still visible in the selected editor is configuration work too.
                // Flush it before comparing with disk so closing cannot bypass the dirty
                // check merely because the reader had not pressed Apply first.
                sourceWorkbench.applyEdits();
            } catch (Exception invalidEdits) {
                reportGenerationError(invalidEdits);
                return;
            }
        }
        ModelBuilderCloseGuard.State state = new ModelBuilderCloseGuard.State(
                hasUnsavedChanges(),
                runState.unsavedInstanceCount(),
                running);
        switch (ModelBuilderCloseGuard.ask(this, state)) {
            case KEEP_OPEN -> { }
            case SAVE_AND_CLOSE -> {
                if (saveEverything(true)) closeApplicationNow();
            }
            case CLOSE_WITHOUT_SAVING -> closeApplicationNow();
            case CANCEL_AND_CLOSE -> {
                // Keep the clients and window alive until both workers report idle. A
                // blocking HTTP request may take a moment to observe its interruption.
                closeWhenIdle = true;
                querySession.runner().cancel();
                processRunner.cancel();
                closeAfterCancellationIfIdle();
            }
        }
    }

    private void closeAfterCancellationIfIdle() {
        if (closeWhenIdle && !processRunner.isRunning()
                && !querySession.runner().isRunning()) {
            closeApplicationNow();
        }
    }

    private void closeApplicationNow() {
        if (closingApplication) return;
        closingApplication = true;
        closeWhenIdle = false;
        // Re-enter the normal JFrame close path only after the guard has settled the
        // decision. EXIT_ON_CLOSE preserves the application's existing process-exit
        // semantics, including when auxiliary log/instances windows are still open.
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        dispatchEvent(new java.awt.event.WindowEvent(
                this, java.awt.event.WindowEvent.WINDOW_CLOSING));
    }

    /** This project is now exactly what {@code file} holds. */
    private void markSaved(File file, ProjectSaveGuard guard) {
        openModelFile = file;
        saveGuard = guard;
        savedSignature = file == null ? "" : modelSignature(projectModel);
    }

    private ProjectSaveGuard guardForModel(File file) throws java.io.IOException {
        File snapshot = snapshotBesideModel(file);
        String key = file.getName().replaceFirst("\\.model\\.json$", "");
        return snapshot == null ? ProjectSaveGuard.capture(storage, key, file)
                : ProjectSaveGuard.capture(storage, key, file, snapshot);
    }

    private ProjectSaveGuard activeSaveGuard() {
        return saveGuard == null ? ProjectSaveGuard.newProject(storage, projectModel.name())
                : saveGuard;
    }

    /** The folder this project lives in, or null for a draft that has no home yet. */
    private File openDomainDir() {
        if (openModelFile != null && openModelFile.getParentFile() != null) {
            return openModelFile.getParentFile();
        }
        File model = domainModelFile(projectModel.name());
        return model == null ? null : model.getParentFile();
    }

    private boolean confirmDiscardChanges(String title) {
        // Nothing to discard, nothing to ask. A dialog that always appears is one a
        // reader learns to dismiss without reading, which is exactly when it needed to
        // be read.
        if (!hasUnsavedChanges()) return true;
        int c = JOptionPane.showConfirmDialog(
                this,
                "Discard any unsaved changes to \"" + projectModel.name()
                        + "\" and continue?\n"
                        + "(Use \"" + (projectModel.isModel() ? "Save model" : "Save domain")
                        + "\" first to keep them.)",
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
            refreshProjectPicker();
            return;
        }
        if (!confirmDiscardChanges("Switch domain")) {
            refreshProjectPicker();
            return;
        }
        doLoadDomain(model);
        refreshProjectPicker();
    }

    // Loads a domain's model in place (no confirm); shared by switch and the
    // post-delete fallback.
    private boolean doLoadDomain(File model) {
        try {
            ProjectSaveGuard guard = guardForModel(model);
            GeneratedProjectModel loaded = new GeneratedProjectModelStore().load(model);
            domain.datasource.graph.GraphDiscoveryState ledger =
                    graphDiscoveryBeside(model);
            guard.check();
            // The caller has already confirmed that pending changes may be discarded.
            replaceOpenProject(loaded, ledger);
            modelChanged();
            classModelPanel.selectClass(projectModel.rootClass());
            syncDepthSpinnerToActiveClass();
            refreshProjectKindUi();
            // Last, after the selection and refreshes: those can announce a change, and
            // a load that marked itself saved too early would then look edited.
            markSaved(model, guard);
            rememberCurrentDomain();
            logWindow.info("Loaded " + (projectModel.isModel() ? "model" : "domain")
                    + " \"" + projectModel.name() + "\".");
            return true;
        } catch (Exception ex) {
            reportGenerationError(ex);
            return false;
        }
    }

    private void newProject() {
        JTextField nameField = new JTextField(22);
        JComboBox<GeneratedProjectModel.ProjectKind> kind = new JComboBox<>(
                GeneratedProjectModel.ProjectKind.values());
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Name:"));
        form.add(nameField);
        form.add(new JLabel("Kind:"));
        form.add(kind);
        if (JOptionPane.showConfirmDialog(this, form, "New domain or model",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
                != JOptionPane.OK_OPTION) return;
        String name = nameField.getText().trim();
        if (name.isBlank()) {
            return;
        }
        String requestedKey = dataset.DomainStorage.key(name);
        String existingName = storage.modelBackedNames().stream()
                .filter(candidate -> dataset.DomainStorage.key(candidate).equals(requestedKey))
                .findFirst().orElse(null);
        if (existingName != null) {
            int c = JOptionPane.showConfirmDialog(this,
                                                  "A project \"" + existingName
                                                          + "\" already uses this name. Switch to it?",
                                                  "Project exists", JOptionPane.OK_CANCEL_OPTION);
            if (c == JOptionPane.OK_OPTION) {
                switchToDomain(existingName);
            } else {
                refreshProjectPicker();
            }
            return;
        }
        if (!confirmDiscardChanges("New project")) {
            refreshProjectPicker();
            return;
        }

        GeneratedProjectModel fresh = new GeneratedProjectModel();
        fresh.name(name);
        fresh.projectKind((GeneratedProjectModel.ProjectKind) kind.getSelectedItem());
        // Start with one neutrally-named root class the user then configures.
        fresh.rootClass().className(
                GeneratedViewableSourceGenerator.sanitizeClassName(name));
        replaceOpenProject(fresh, domain.datasource.graph.GraphDiscoveryState.EMPTY);
        modelChanged();
        classModelPanel.selectClass(projectModel.rootClass());
        syncDepthSpinnerToActiveClass();
        refreshProjectPicker();
        refreshProjectKindUi();
        logWindow.info("Created new " + (fresh.isModel() ? "model" : "domain")
                + " \"" + name + "\". Configure its classes, then save it.");
    }

    // Rename in place: move the domain folder and re-key the files inside, then
    // update the registry entry (if any). Nothing destructive beyond the move.
    private void renameDomain() {
        String oldName = projectModel.name();
        String kind = projectModel.isModel() ? "model" : "domain";
        String name = (String) JOptionPane.showInputDialog(
                this, "Rename " + kind + ":", "Rename " + kind,
                JOptionPane.QUESTION_MESSAGE, null, null, oldName);
        if (name == null) {
            return;
        }
        name = name.trim();
        if (name.isBlank() || name.equals(oldName)) {
            return;
        }

        dataset.DomainStorage.Rename rename;
        try {
            String target = name;
            rename = ProjectSaveGuard.exclusive(storage, () -> {
                activeSaveGuard().check();
                var result = storage.rename(oldName, target);
                if (result instanceof dataset.DomainStorage.Rename.Done) {
                    File moved = storage.modelFile(target);
                    if (openModelFile != null) {
                        saveGuard = guardForModel(moved);
                        openModelFile = moved;
                    }
                }
                return result;
            });
        } catch (Exception conflict) {
            reportGenerationError(conflict);
            return;
        }
        switch (rename) {
            case dataset.DomainStorage.Rename.FolderTaken taken -> JOptionPane.showMessageDialog(
                    this, "A project folder already exists at\n" + taken.existing().getPath()
                            + "\nChoose a different name.",
                    "Rename " + kind, JOptionPane.WARNING_MESSAGE);
            case dataset.DomainStorage.Rename.Failed failed ->
                    reportGenerationError(failed.cause());
            case dataset.DomainStorage.Rename.Done done -> {
                projectModel.name(done.name()); // file paths now use the new key
                rememberInstanceClassVisibility(instancesWindow.hiddenClassSections());
                rememberCurrentDomain();
                modelChanged();
                refreshProjectPicker();
                logWindow.info("Renamed " + kind + " \"" + oldName + "\" -> \""
                        + done.name() + "\".");
            }
        }
    }

    private void deleteDomain() {
        String name = projectModel.name();
        String kind = projectModel.isModel() ? "model" : "domain";
        File dir = projectDataDir();
        int c = JOptionPane.showConfirmDialog(this,
                                              "Delete " + kind + " \"" + name + "\"?\n\n"
                                                      + "Removes it from the registry and deletes:\n"
                                                      + dir.getPath() + "\n\nThis cannot be undone.",
                                              "Delete " + kind,
                                              JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (c != JOptionPane.OK_OPTION) {
            refreshProjectPicker();
            return;
        }
        try {
            activeSaveGuard().write(() -> {
                storage.delete(name);
                return null;
            });
            logWindow.info("Deleted " + kind + " \"" + name + "\".");
        } catch (Exception ex) {
            reportGenerationError(ex);
            return;
        }

        // Switch to whatever else is saved — the same list the picker shows, so a model
        // is a candidate even though nothing registered it. Falling through to the demo
        // when that fails matters as much as the switch itself: the picker re-adds the
        // open project by name, so leaving the deleted one loaded puts it back in the
        // list it was just removed from.
        String next = storage.anySavedNameOtherThan(name);
        File nextFile = next == null ? null : domainModelFile(next);
        boolean switched = nextFile != null && nextFile.isFile() && doLoadDomain(nextFile);
        if (!switched) {
            replaceOpenProject(GeneratedProjectModel.constellationDemo(),
                    domain.datasource.graph.GraphDiscoveryState.EMPTY);
            modelChanged();
            classModelPanel.selectClass(projectModel.rootClass());
            syncDepthSpinnerToActiveClass();
            // Deleting a model and landing on a domain changes the kind, and every
            // control labelled by it.
            refreshProjectKindUi();
        }
        refreshProjectPicker();
    }

    private void loadSavedModelIfPresent() {
        String remembered;
        try {
            remembered = PREFERENCES.get(LAST_DOMAIN, "");
        } catch (RuntimeException unavailable) {
            remembered = "";
        }
        File f = storage.preferredModelFile(remembered, projectModel.name());
        if (f == null) {
            return;
        }
        try {
            ProjectSaveGuard guard = guardForModel(f);
            GeneratedProjectModel loaded = new GeneratedProjectModelStore().load(f);
            domain.datasource.graph.GraphDiscoveryState ledger = graphDiscoveryBeside(f);
            guard.check();
            // Startup uses the same replacement boundary as an interactive switch.
            replaceOpenProject(loaded, ledger);
            modelChanged();
            syncDepthSpinnerToActiveClass();
            markSaved(f, guard);
            rememberCurrentDomain();
        } catch (Exception ex) {
            System.err.println("Could not load saved model " + f.getPath()
                                       + ": " + ex.getMessage());
        }
    }

    /** Remember only a durable domain. An unsaved draft cannot be restored on the
     * next startup and therefore must not displace the last usable preference. */
    private void rememberCurrentDomain() {
        String name = projectModel.name();
        File model = storage.modelFileOf(name);
        if (model == null || !model.isFile()) return;
        try {
            PREFERENCES.put(LAST_DOMAIN, name);
        } catch (RuntimeException unavailable) {
            // This preference is a convenience; inability to write it must never
            // prevent closing, switching or saving a domain.
        }
    }

    /** The generated-instances class choice is reader presentation, not authored
     * model data. Keep it beside the other local ModelBuilder preferences, scoped to
     * the current domain/model, so regeneration and application restart preserve it. */
    private java.util.Set<String> rememberedInstanceClassVisibility() {
        try {
            String stored = instanceClassVisibilityNode().get("hiddenClasses", "");
            return stored.lines().map(String::trim).filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(
                            java.util.LinkedHashSet::new));
        } catch (RuntimeException unavailable) {
            return java.util.Set.of();
        }
    }

    private void rememberInstanceClassVisibility(java.util.Set<String> hidden) {
        try {
            Preferences node = instanceClassVisibilityNode();
            node.put("project", instanceClassVisibilityScope());
            node.put("hiddenClasses", String.join("\n",
                    hidden == null ? java.util.Set.of() : hidden));
        } catch (RuntimeException unavailable) {
            // This preference is a convenience; inability to write it must never
            // affect generation, saving or switching projects.
        }
    }

    private Preferences instanceClassVisibilityNode() {
        String id = java.util.UUID.nameUUIDFromBytes(instanceClassVisibilityScope()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return PREFERENCES.node(INSTANCE_CLASS_VISIBILITY).node(id);
    }

    private String instanceClassVisibilityScope() {
        return (projectModel.isModel() ? "model:" : "domain:") + projectModel.name();
    }

    // Loads a project model (config) from a chosen *.model.json, replacing the
    // current one. (Instances are reloaded separately via "Load instances".)
    // A project is one folder under data/wikidata/. A domain may also hold its
    // .ruletree/.snapshot files; a model holds configuration only. The user picks the
    // FOLDER (files are shown but not selectable — which file is a technical detail)
    // and we derive the model file.
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

    /**
     * The instance snapshot paired with one exact model file.
     *
     * <p>A project directory can also contain {@code *.graph.snapshot.json} files.
     * Looking for an arbitrary file ending in {@code .snapshot.json} therefore loads a
     * graph constraint's annotation records instead of the project's instances whenever
     * directory iteration happens to return that file first.
     */
    static File snapshotBesideModel(File model) {
        if (model == null || model.getParentFile() == null) return null;
        String name = model.getName();
        String suffix = ".model.json";
        if (!name.endsWith(suffix)) return null;
        return new File(model.getParentFile(),
                name.substring(0, name.length() - suffix.length()) + ".snapshot.json");
    }

    private void loadProject() {
        File dir = chooseDomainDir("Load project — pick its folder under data/wikidata/");
        if (dir == null) {
            return;
        }
        File model = findInDir(dir, ".model.json");
        if (model == null) {
            JOptionPane.showMessageDialog(this,
                                          "No *.model.json found in\n" + dir.getPath(),
                                          "Load project", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            ProjectSaveGuard guard = guardForModel(model);
            GeneratedProjectModel loaded = new GeneratedProjectModelStore().load(model);
            domain.datasource.graph.GraphDiscoveryState ledger =
                    graphDiscoveryBeside(model);
            guard.check();
            replaceOpenProject(loaded, ledger);
            modelChanged();
            classModelPanel.selectClass(projectModel.rootClass());
            syncDepthSpinnerToActiveClass();
            refreshProjectPicker();
            refreshProjectKindUi();
            markSaved(model, guard);
            logWindow.info("Loaded project configuration from " + model.getName());
        } catch (Exception ex) {
            reportGenerationError(ex);
        }
    }

    /** The graph ledger is generated data owned by the snapshot beside the model. */
    private static domain.datasource.graph.GraphDiscoveryState graphDiscoveryBeside(File model)
            throws java.io.IOException {
        File snapshot = snapshotBesideModel(model);
        return snapshot == null || !snapshot.isFile()
                ? domain.datasource.graph.GraphDiscoveryState.EMPTY
                : new WikidataDynamicObjectJsonStore().loadGraphDiscovery(snapshot);
    }

    private void loadSavedInstances() {
        // The open domain knows where its instances are — asking for the folder made
        // the reader answer a question the application had already answered, and let
        // them answer it with somebody else's domain.
        File dir = openDomainDir();
        File file = snapshotBesideModel(openModelFile);
        if (file == null && dir != null) {
            file = new File(dir, projectKey() + ".snapshot.json");
        }
        if (file == null || !file.isFile()) {
            JOptionPane.showMessageDialog(this,
                                          dir == null
                                                  ? "\"" + projectModel.name() + "\" has not been "
                                                          + "saved yet, so it has no instances to load."
                                                  : "No saved instances for \"" + projectModel.name()
                                                          + "\" in\n" + dir.getPath()
                                                          + "\n(generate, then \""
                                                          + (projectModel.isModel()
                                                                  ? "Save model" : "Save domain")
                                                          + "\")",
                                          "Load instances", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            loadSavedInstances(file);
        } catch (Exception ex) {
            reportGenerationError(ex);
        }
    }

    private void loadSavedInstances(File file) throws Exception {
        sourceWorkbench.applyEdits();
        ProjectSaveGuard guard = activeSaveGuard();
        guard.check();
        var loaded = domain.build.ProjectLoad.load(projectModel, storage, file, logWindow::info);
        guard.check();
        installSavedInstances(file, loaded, true);
    }

    private void installSavedInstances(File file,
                                       domain.build.ProjectLoad.Loaded loaded,
                                       boolean revealInstances) {
        runState.restoreGraphLedger(loaded.projection().graphDiscovery());
        graphResults.restore(loaded.projection().objects(), this::logInfo);
        acceptGenerationRun(loaded.run(), true);
        if (revealInstances) instancesWindow.show();
        logWindow.info("Loaded " + loaded.run().dynamicObjects().size()
                + " saved object(s) from " + file.getName()
                + "; mapped " + loaded.run().instances().size()
                + " instance(s).");
        if (loaded.stale()) {
            JOptionPane.showMessageDialog(this, process.swing.Dialogs.wrapped(loaded.staleWarning()),
                    "Instances may be stale", JOptionPane.WARNING_MESSAGE);
        }
    }

    // Membership discovery is narrower than the general source-QID action: its preview
    // describes P31 membership, so it refuses to replace seeds, selections or another
    // relation. Pending editor values are applied before that decision is made.
    private String useMembershipType(domain.workbench.ui.WorkbenchSelections.Entity value) {
        sourceWorkbench.applyEdits();
        GeneratedClassModel clazz = activeClass();
        String other = PopulationSourceEditing.wikidataMembershipRefusal(clazz);
        if (!other.isEmpty()) return other;
        EntityBound.Kind kind = clazz.membership().kind();
        if (kind != EntityBound.Kind.UNBOUNDED && kind != EntityBound.Kind.RELATION) {
            return kind == EntityBound.Kind.EXPLICIT
                    ? "The class uses explicit seed QIDs; they were not replaced."
                    : "The class uses a named selection; it was not replaced.";
        }
        if (kind == EntityBound.Kind.RELATION
                && (!"P31".equalsIgnoreCase(clazz.membership().relationPid())
                        || clazz.membership().qualifierCondition() != null)) {
            return "The class does not use a plain P31 membership; it was not replaced.";
        }
        setSourceQid(clazz, value.qid(), value.label());
        return "";
    }

    private void useSourceQid(String qid, String label) {
        sourceWorkbench.applyEdits();
        String other = PopulationSourceEditing.wikidataMembershipRefusal(activeClass());
        if (!other.isEmpty()) {
            JOptionPane.showMessageDialog(this, other, "Population source",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        setSourceQid(activeClass(), qid, label);
    }

    // The one mutation shared by Membership, WikiProject, Explore and Category tools.
    // It used to write instanceMapping.sourceQid, a field membership no longer reads.
    private void setSourceQid(GeneratedClassModel c, String qid, String label) {
        if (qid == null || !WikidataIds.isQid(qid)) {
            return;
        }
        // Replaces the LEADING target and keeps the rest, which is what this did when
        // the leading one was its own field: the editor still shows the first target as
        // "Wikidata type" and the others as "Also include types".
        java.util.List<String> targets = new java.util.ArrayList<>();
        targets.add(qid);
        c.membership().qids().stream().skip(1)
                .filter(existing -> !existing.equals(qid)).forEach(targets::add);
        String pid = c.membership().relationPid();
        c.membership(EntityBound.qualifiedRelation(
                pid.isBlank()
                        ? domain.config.MembershipPattern.DEFAULT_PROPERTY : pid,
                targets, c.membership().includeDescendants(),
                c.membership().qualifierCondition()));
        c.instanceMapping().sourceLabel(label == null ? "" : label);
        sourceWorkbench.edit(c);
        modelChanged();
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
            if (qid != null && WikidataIds.isQid(qid) && !c.seedQids().contains(qid)) {
                c.seedQids().add(qid);
                added++;
            }
        }
        sourceWorkbench.edit(c);
        modelChanged();
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
        java.util.List<String> targets =
                new java.util.ArrayList<>(c.membership().qids());
        int added = 0;
        for (String qid : qids) {
            if (qid != null && WikidataIds.isQid(qid) && !targets.contains(qid)) {
                targets.add(qid);
                added++;
            }
        }
        String pid = c.membership().relationPid();
        pid = pid.isBlank()
                ? domain.config.MembershipPattern.DEFAULT_PROPERTY : pid;
        c.membership(EntityBound.qualifiedRelation(
                pid, targets, c.membership().includeDescendants(),
                c.membership().qualifierCondition()));
        sourceWorkbench.edit(c);
        modelChanged();
        logWindow.info("Added " + added + " relation target(s) to \""
                               + c.className() + "\" (total " + targets.size()
                               + "). They join the membership relation (" + pid
                               + ") as additional targets.");
    }

    // The class the toolbar actions operate on: the one selected in the class
    // tree (or the owner of a selected field), else the root class.
    private GeneratedClassModel activeClass() {
        return classModelPanel.selectedClassOrRoot();
    }

    private void syncDepthSpinnerToActiveClass() {
        GeneratedClassModel c = activeClass();
        if (c != null) runSection.syncDepth(c.generationDepth());
    }

    private void storeGenerationDepth(int depth) {
        GeneratedClassModel c = activeClass();
        if (c != null) c.generationDepth(depth);
    }


    // Where a domain's files live is dataset.DomainStorage's business; this frame only says
    // which domain it is currently editing. The layout — "Constellations" ->
    // data/wikidata/constellations/ — is the one the web client and "Load instances" read.
    private String projectKey() { return dataset.DomainStorage.key(projectModel.name()); }

    private static String domainKey(String name) { return dataset.DomainStorage.key(name); }

    private File projectDataDir() { return storage.directory(projectModel.name()); }

    private File snapshotFile() { return storage.snapshotFile(projectModel.name()); }

    private File ruleTreeFile() { return storage.ruleTreeFile(projectModel.name()); }

    private File modelFile() { return storage.modelFile(projectModel.name()); }

    // Saves config (the editable project model), the compiled rule tree, and
    // the generated instances together into the project's data directory — the
    // instances file being exactly what the web client and "Load instances" read.
    // A model's generation signature: the rule tree it compiles to is exactly
    // what drives generation, so two models with the same signature produce the
    // same instances. Used to detect a model drifting past its saved snapshot.
    private static String modelSignature(GeneratedProjectModel model) {
        return domain.build.DomainSave.signature(model);
    }

    // The distinct class-types stamped on a set of generated objects.


    private void saveEverything() {
        saveEverything(false);
    }

    /** @return true only when every requested durable write completed. */
    private boolean saveEverything(boolean closingAfterSave) {
        Window dialogOwner = process.swing.Dialogs.owner(this);
        String projectKind = projectModel.isModel() ? "model" : "domain";
        GeneratedProjectModel modelToSave = projectModel;
        boolean recoverCompletedRun = false;
        try {
            sourceWorkbench.applyEdits();
        } catch (LinkageError brokenRuntime) {
            // A live JVM can straddle an incremental/parallel compile: an editor class
            // already loaded from one build may reference a helper absent from that
            // class loader. Do not let that strand a completed 20–30 minute generation.
            // The run owns the exact immutable model that produced its objects, so it
            // can still be saved as a consistent model/rules/snapshot triple without
            // consulting the broken live editor.
            if (runState.current() == null || runState.current().modelSnapshot() == null
                    || runState.current().dynamicObjects() == null
                    || runState.current().dynamicObjects().isEmpty()) {
                reportGenerationError(brokenRuntime);
                return false;
            }
            int recover = JOptionPane.showConfirmDialog(dialogOwner,
                    "The live configuration editor could not be applied because the "
                            + "running application has an inconsistent classpath:\n"
                            + brokenRuntime + "\n\n"
                            + "Save the completed generation using the immutable model "
                            + "snapshot that produced it?\n"
                            + "Pending editor changes will not be included.",
                    "Recover completed generation",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (recover != JOptionPane.OK_OPTION) return false;
            modelToSave = runState.current().modelSnapshot();
            recoverCompletedRun = true;
        }
        if (!recoverCompletedRun) {
            // Depth is per-class (saved on each class via the spinner change listener);
            // make sure the active class has the latest spinner value.
            storeGenerationDepth(runSection.depth());
            restampRenamedInstances();
        }

        domain.build.ProjectSave save;
        ProjectSaveGuard guard = activeSaveGuard();
        try {
            save = domain.build.ProjectSave.plan(
                    new domain.build.ProjectSave.Input(modelToSave,
                            runState.current() == null ? null : new domain.build.ProjectSave.Run(
                                    runState.current().dynamicObjects(), runState.current().loadedDeclarations(),
                                    runState.current().selfReferenceAudit().ledger(),
                                    runState.current().modelSnapshot(),
                                    runState.current().generatedFromSignature()),
                            runState.graphLedger(), graphResults.all()),
                    storage, guard);
        } catch (Exception unreadableSnapshot) {
            reportGenerationError(unreadableSnapshot);
            return false;
        }

        for (domain.build.ProjectSave.Warning warning : save.warnings()) {
            boolean stale = warning.kind()
                    == domain.build.ProjectSave.Warning.Kind.STALE_INSTANCES;
            int d = JOptionPane.showConfirmDialog(dialogOwner,
                    process.swing.Dialogs.wrapped(warning.message() + "\n\n" + (stale
                            ? "Regenerate (Cancel, then \"Generate class instances\") "
                                    + "before saving, or save anyway?"
                            : "Use \"Generate domain\" to keep every class. Save anyway?")),
                    stale ? "Model changed since generation" : "Overwriting a multi-class snapshot",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (d != JOptionPane.OK_OPTION) return false;
        }

        // Confirm BEFORE writing — show the exact paths and what each will get, so
        // Escape actually cancels.
        if (!closingAfterSave) {
            String plan = "Save the " + projectKind + " \"" + modelToSave.name()
                    + "\" — write these files?\n\n" + String.join("\n", save.planLines());
            int choice = JOptionPane.showConfirmDialog(
                    dialogOwner, process.swing.Dialogs.wrapped(plan), "Save " + projectKind,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return false;
            }
        }

        try {
            domain.build.ProjectSave.Result result = save.write();
            String report = String.join("\n", result.report()) + "\n";
            rememberCurrentDomain();
            sourceWorkbench.refreshDomainOverview();
            // What is on disk is now what is open, so switching away asks nothing.
            markSaved(modelFile(), guard);
            runState.markSaved();
            logWindow.info("Saved " + projectKind + " \"" + projectModel.name()
                    + "\":\n" + report);

            if (!closingAfterSave) {
                String hint = instanceCountHint(result.instancesWritten() == 0
                        ? -1 : result.instancesWritten());
                process.swing.Dialogs.showPersistenceSuccess(dialogOwner,
                        "Save " + projectKind, projectModel.name(),
                        report + (hint.isBlank() ? "" : "\n" + hint));
            }
            return true;
        } catch (Exception ex) {
            reportGenerationError(ex);
            return false;
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

        boolean runMatchesClass = runState.current() != null && runState.current().runtime() != null
                && runState.current().modelSnapshot() != null
                && runState.current().modelSnapshot().rootClass().className().equals(cls.className());
        boolean modelUnchanged = runMatchesClass
                && modelSignature(projectModel)
                .equals(modelSignature(runState.current().modelSnapshot()));

        if (modelUnchanged) {
            source = runState.current().runtime().source();
            title = runState.current().runtime().qualifiedClassName();
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
