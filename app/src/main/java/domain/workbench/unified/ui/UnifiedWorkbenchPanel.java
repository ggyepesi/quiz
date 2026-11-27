package domain.workbench.unified.ui;

import dataset.DomainStorage;
import domain.DomainModel;
import domain.DomainSchemas;
import domain.build.ProjectBuild;
import domain.build.GraphResults;
import domain.runtime.WikidataDynamicObject;
import domain.workbench.modelbuilder.ui.ProjectInstancesPresentation;
import domain.compile.transform.TransformationExecution;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.GeneratedProjectModel;
import domain.config.GeneratedProjectModelStore;
import domain.config.FieldSourceBindings;
import domain.config.FieldDefinition;
import domain.config.BuildOperation;
import domain.config.ModelSourceExecutionPlan;
import domain.config.OwnedFieldSources;
import domain.curation.CorrectionsSidecar;
import domain.curation.ManualCuration;
import domain.datasource.Datasources;
import domain.datasource.discovery.ui.DiscoveryWorkbench;
import domain.transform.ui.BoundedInstanceSelection;
import domain.transform.pipeline.ui.FilterGroupsPanel;
import domain.datasource.api.ConfigurationElement;
import domain.datasource.api.DatasourceRegistry;
import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.api.SourceExecutionPlan;
import domain.transform.ui.ConfiguredFieldEvidenceActions;
import domain.transform.ui.ProjectBacking;
import domain.transform.ui.FieldDefinitions;
import domain.transform.ui.TransformController;
import domain.transform.app.DomainCatalog;
import domain.transform.ui.ValidationPanel;
import domain.transform.ui.WorkbenchPurpose;
import domain.workbench.configuration.ui.ProjectConfigurationPanel;
import domain.workbench.modelbuilder.ui.SubclassDiscoveryPanel;
import domain.workbench.execution.ui.BuildProjectWorkflow;
import domain.workbench.execution.ui.QueryObjectResultPanel;
import domain.workbench.execution.ui.SwingQuerySession;
import domain.workbench.modelbuilder.ui.GraphRunWorkflow;
import domain.workbench.ui.WorkbenchActionResultNavigator;
import domain.workbench.ui.WorkbenchResultViews;
import objectview.utils.swing.WrapLayout;
import process.swing.Dialogs;
import process.swing.SwingCancellationConfirmation;
import process.swing.SwingProcessInputHandler;
import process.swing.SwingProcessRunner;
import process.swing.workflow.SwingProcessWorkflow;
import work.QueryContext;
import work.Query;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import javax.swing.JRootPane;
import java.awt.FlowLayout;
import java.util.Locale;
import java.util.List;
import java.io.File;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** One opened project for field acquisition, local transformations and builds. */
public final class UnifiedWorkbenchPanel extends JPanel implements AutoCloseable {
    private DomainModel domain;
    private ProjectBacking project;
    private final SwingQuerySession queries;
    private ProjectConfigurationPanel configuration;
    private TransformController transform;
    private final WorkbenchActionResultNavigator actions = new WorkbenchActionResultNavigator();
    private final DiscoveryWorkbench discovery;
    private final SubclassDiscoveryPanel membershipDiscovery = new SubclassDiscoveryPanel(false);
    private final WorkbenchActionResultNavigator.Action previewAction;
    private final WorkbenchActionResultNavigator.Action filterGroupsAction;
    private final JPanel filterGroupsDetail = new JPanel(new BorderLayout());
    private FilterGroupsPanel filterGroups;
    private boolean filterGroupsSelected;
    private final WorkbenchActionResultNavigator.Action buildAction;
    private final WorkbenchActionResultNavigator.Action loadInstancesAction;
    private final JPanel loadInstancesDetail = new JPanel(new BorderLayout());
    private final JButton loadInstances = new JButton("Load instances");
    private final JButton showLoadedInstances = new JButton("Show instances");
    /** The instances in the instances window, whichever of Load and Build put them there. */
    private DomainModel shownInstances;
    private final JButton cancelLoadInstances = new JButton("Cancel load");
    private final JTextArea loadInstancesStatus = new JTextArea();
    /** The one instances view: Load instances and a completed build both fill it. The
     *  application hosts it in its own window, as ModelBuilder does. */
    private final QueryObjectResultPanel instancesView = new QueryObjectResultPanel();
    private Consumer<InstancesShown> instancesShown = ignored -> { };
    private boolean loadingInstances;
    private boolean loadInstancesActionSelected;
    private final WorkbenchActionResultNavigator.Action graphRunAction;
    private final WorkbenchActionResultNavigator.Action graphReviewAction;
    private final GraphDecisionReviewPanel graphReview;
    private final ConfiguredFieldEvidenceActions evidenceActions;
    private final SwingProcessRunner buildRunner;
    private final SwingProcessRunner graphRunner;
    private final JPanel graphRunDetail = new JPanel(new BorderLayout());
    private final JButton planGraph = new JButton("Plan graph run");
    private final JLabel graphRunStatus = new JLabel();
    private final JPanel buildDetail = new JPanel(new BorderLayout());
    private final JLabel buildStatus = new JLabel();
    private final JButton planBuild = new JButton("Plan build");
    private final JButton editBuild = new JButton("Edit build steps");
    private final JSplitPane workspace;
    private final WorkbenchResultViews previewResults = new WorkbenchResultViews();
    private final JButton preview = new JButton("Preview population");
    private final JButton cancelPreview = new JButton("Cancel preview");
    private final JLabel previewStatus = new JLabel();
    private final DatasourceRegistry datasources = Datasources.standard();
    private final JTextArea planText = new JTextArea(8, 34);
    private final JButton save;
    private final JLabel saveStatus = new JLabel();
    private final JPanel acquisitionDetail = new JPanel(new BorderLayout());
    private final JPanel acquisitionValues = new JPanel(new BorderLayout());
    private final String saveVerb;
    private SourceExecutionPlan plan;
    private SourceExecutionPlan selectedFieldPlan;
    private String planProblem;
    private boolean saving;
    private boolean loadingValues;
    private boolean previewing;
    private boolean building;
    private boolean runningGraph;
    private boolean graphPlanOpen;
    private boolean graphRunActionSelected;
    private boolean buildPlanOpen;
    private boolean buildActionSelected;
    private DomainModel builtDomain;
    private GeneratedClassModel graphAwaitingReview;
    private boolean previewCompleted;
    private boolean previewActionSelected;
    private boolean loadActionSelected;
    private boolean acquisitionTargetCurrent;
    private GeneratedFieldModel selectedField;
    private GeneratedClassModel selectedClass;
    private GeneratedClassModel previewInput;
    private ValidationPanel valuePanel;
    private EvidenceTarget evidenceTarget;
    private ValidationPanel runningEvidence;

    private record EvidenceTarget(GeneratedFieldModel field, FieldDefinition definition,
            List<SourceBinding> recipes) { }

    public UnifiedWorkbenchPanel(DomainModel domain) {
        this(domain, new SwingQuerySession(new QueryContext()));
    }

    public UnifiedWorkbenchPanel(DomainModel domain, SwingQuerySession queries) {
        super(new BorderLayout(4, 4));
        this.domain = domain;
        this.queries = queries;
        buildRunner = new SwingProcessRunner(queries.runner().context(), queries.logs(),
                new SwingProcessInputHandler(this));
        graphRunner = new SwingProcessRunner(queries.runner().context(), queries.logs(),
                new SwingProcessInputHandler(this));
        transform = new TransformController(domain, null);
        project = Objects.requireNonNull(domain.capability(ProjectBacking.class),
                "Open a model-backed project to edit field configuration");
        queries.logs().saveContext(project.projectName(),
                project.modelFile().getAbsoluteFile().getParentFile().toPath());
        saveVerb = "Save " + project.projectKind().name().toLowerCase(Locale.ROOT);
        save = new JButton(saveVerb + "…");
        save.addActionListener(event -> confirmSave());
        planText.setName("field.acquisition.plan");
        planText.setEditable(false);
        planText.setLineWrap(true);
        planText.setWrapStyleWord(true);

        discovery = new DiscoveryWorkbench(transform, actions, queries);
        var source = discovery.contextSource(DiscoveryWorkbench.Source.WIKIDATA);
        var acquisition = actions.addAction(source, "field-acquisition-plan", "Field acquisition plan",
                new JScrollPane(planText), null);
        var load = discovery.addContextAction(DiscoveryWorkbench.Source.WIKIDATA,
                "field-values", "Load the current configured field", acquisitionDetail, null);
        membershipDiscovery.setQueryRunner(queries.runner());
        actions.addAction(source, "membership-types", "Find membership types", membershipDiscovery, null);
        evidenceActions = new ConfiguredFieldEvidenceActions(actions,
                discovery.contextSource(DiscoveryWorkbench.Source.WIKIPEDIA), "field-evidence",
                this::evidencePanel);
        refreshEvidence();
        var logs = new JButton("Query logs…");
        logs.addActionListener(event -> queries.showLogs(this));
        acquisitionDetail.add(acquisitionValues, BorderLayout.CENTER);
        var transformSource = actions.addSource("transform", "Transform", null);
        filterGroupsAction = actions.addAction(transformSource, "filter-groups", "Filter groups",
                filterGroupsDetail, this::showFilterGroups);
        var previewDetail = new JPanel(new BorderLayout());
        var previewTools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        SwingCancellationConfirmation.wire(cancelPreview, this,
                () -> previewing && queries.runner().isRunning(), queries.runner()::cancel);
        previewTools.add(preview);
        previewTools.add(cancelPreview);
        previewTools.add(previewStatus);
        previewDetail.add(previewTools, BorderLayout.NORTH);
        previewResults.setName("transform.preview.results");
        previewDetail.add(previewResults, BorderLayout.CENTER);
        previewAction = actions.addAction(transformSource, "preview-population", "Preview population",
                previewDetail, null);
        preview.addActionListener(event -> previewPopulation());
        queries.runner().onRunningChanged(running -> {
            if (previewing) {
                if (!running) finishPreview();
            } else {
                configuration.setEditingEnabled(!running);
                updateSave();
            }
        });
        var projectActions = actions.addSource("project", "Project", null);
        var loadInstancesTools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        loadInstancesTools.add(loadInstances);
        loadInstancesTools.add(cancelLoadInstances);
        showLoadedInstances.setName("load.instances.show");
        showLoadedInstances.setEnabled(false);
        showLoadedInstances.addActionListener(event -> {
            revealInstances(true);
        });
        loadInstancesTools.add(showLoadedInstances);
        loadInstancesStatus.setName("load.instances.status");
        loadInstancesStatus.setEditable(false);
        loadInstancesStatus.setOpaque(false);
        loadInstancesStatus.setLineWrap(true);
        loadInstancesStatus.setWrapStyleWord(true);
        var loadInstancesHeader = new JPanel(new BorderLayout());
        loadInstancesHeader.add(loadInstancesTools, BorderLayout.NORTH);
        loadInstancesHeader.add(loadInstancesStatus, BorderLayout.CENTER);
        loadInstancesDetail.add(loadInstancesHeader, BorderLayout.NORTH);
        SwingCancellationConfirmation.wire(cancelLoadInstances, this,
                () -> loadingInstances && queries.runner().isRunning(), queries.runner()::cancel);
        loadInstancesAction = actions.addAction(projectActions, "load-instances", "Load instances",
                loadInstancesDetail, null);
        loadInstances.addActionListener(event -> loadSavedInstances());
        buildAction = actions.addAction(projectActions, "build-project", "Build project", buildDetail, null);
        graphRunAction = actions.addAction(projectActions, "run-graph", "Run graph", graphRunDetail, null);
        planGraph.addActionListener(event -> planGraphRun());
        graphReview = new GraphDecisionReviewPanel(project,
                () -> !busy() && configuration.resolveUnappliedEdits("loading graph annotations"),
                loading -> {
                    configuration.setEditingEnabled(!loading);
                    updateSave();
                }, queries::info, this::updateSave);
        graphReviewAction = actions.addAction(graphRunAction, "review-graph-decisions",
                "Review graph decisions", graphReview, null);
        planBuild.addActionListener(event -> planBuild());
        editBuild.addActionListener(event -> configuration.selectConstruct(project.projectModel()));
        buildRunner.onRunningChanged(running -> {
            building = running;
            configuration.setEditingEnabled(!running);
            updateSave();
        });
        graphRunner.onRunningChanged(running -> {
            runningGraph = running;
            configuration.setEditingEnabled(!running);
            updateSave();
        });
        resetBuild("");
        resetGraphRun("");
        actions.setSelectionListener(selection -> {
            loadActionSelected = load.id().equals(selection.id());
            filterGroupsSelected = filterGroupsAction.id().equals(selection.id());
            previewActionSelected = previewAction.id().source().equals(selection.id().source())
                    && previewAction.id().action().equals(selection.id().action());
            buildActionSelected = buildAction.id().source().equals(selection.id().source())
                    && buildAction.id().action().equals(selection.id().action());
            loadInstancesActionSelected = loadInstancesAction.id().source().equals(selection.id().source())
                    && loadInstancesAction.id().action().equals(selection.id().action());
            if (loadInstancesActionSelected && !loadingInstances) showLoadInstancesReadiness();
            graphRunActionSelected = graphRunAction.id().source().equals(selection.id().source())
                    && graphRunAction.id().action().equals(selection.id().action());
            if (loadActionSelected) refreshAcquisition();
        });
        actions.expandSources();
        actions.selectAction(acquisition);
        compilePlan();
        configuration = projectConfiguration();
        discovery.selection().addHost(new BoundedInstanceSelection.Host() {
            @Override public void selectionChanged(String reason) { }
            @Override public void updateActions() { updateSave(); }
        });
        actions.setMinimumSize(new Dimension(0, ACTIONS_MINIMUM_HEIGHT));
        workspace = new JSplitPane(JSplitPane.VERTICAL_SPLIT, configuration, actions);
        workspace.setResizeWeight(0.72);
        add(workspace, BorderLayout.CENTER);

        var footer = new JPanel(new WrapLayout(FlowLayout.RIGHT, 5, 5));
        footer.add(saveStatus);
        footer.add(logs);
        footer.add(save);
        add(footer, BorderLayout.SOUTH);
        updateSave();
    }

    private ProjectConfigurationPanel projectConfiguration() {
        var panel = ProjectConfigurationPanel.editWorkingConfiguration(project, transform,
                this::constructSelected, this::fieldApplied, this::refreshPreview, this::updateSave);
        // The editors' own minimum (over 600 px) would otherwise win the split on any
        // window shorter than about 870 px and squeeze the actions out of sight.
        panel.setMinimumSize(new Dimension(0, CONFIGURATION_MINIMUM_HEIGHT));
        return panel;
    }

    static final int CONFIGURATION_MINIMUM_HEIGHT = 160;
    static final int ACTIONS_MINIMUM_HEIGHT = 200;

    private boolean canRunGraph() {
        return project.saveGuard() != null && GraphRunWorkflow.hasTraversal(selectedClass)
                && !selectedClass.isImported();
    }

    private void resetGraphRun(String message) {
        graphPlanOpen = false;
        if (graphRunActionSelected) actions.selectAction(graphRunAction);
        actions.clearResults(graphRunAction);
        graphRunStatus.setText(message.isBlank() ? (canRunGraph()
                ? "Plan " + selectedClass.className() + " with its configured start input."
                : "Select a configured local graph class.") : message);
        var controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(planGraph);
        controls.add(graphRunStatus);
        graphRunDetail.removeAll();
        graphRunDetail.add(controls, BorderLayout.NORTH);
        graphRunDetail.revalidate();
        graphRunDetail.repaint();
        planGraph.setEnabled(!busy() && canRunGraph());
    }

    private void planGraphRun() {
        if (busy() || !canRunGraph() || !configuration.resolveUnappliedEdits("planning the graph run")) return;
        var graph = selectedClass;
        var file = graphReview.annotationFile(graph);
        try {
            var action = GraphRunWorkflow.action(project.projectModel(), graph, domain.instances(), file,
                    new GraphRunWorkflow.ResultHandling("Keep annotations", "Close without keeping annotations",
                            "Keep annotations adds these decisions to the open project. "
                                    + saveVerb + " writes " + file.getPath() + ".",
                            () -> graphReview.previousForRun(graph),
                            artifact -> graphReview.keepRun(graph, artifact),
                            () -> actions.selectAction(graphReviewAction)));
            graphPlanOpen = true;
            SwingProcessWorkflow.embed(graphRunner, action, new SwingProcessWorkflow.Embedded(
                    graphRunDetail, () -> {
                        if (!graphPlanOpen || busy() || selectedClass != graph
                                || !configuration.resolveUnappliedEdits("running the graph")) return false;
                        graphPlanOpen = false;
                        queries.logs().registerPipeline("Run graph — " + graph.className(),
                                action.pipeline(), project.projectName(), file.getAbsoluteFile().getParentFile().toPath());
                        return true;
                    }, () -> resetGraphRun(""), WorkbenchResultViews::new,
                    tabs -> actions.publishResults(graphRunAction, tabs, 0, graphRunActionSelected)));
        } catch (Exception failure) {
            resetGraphRun("Could not plan " + graph.className() + ": " + failure.getMessage());
        }
    }

    private void resetBuild(String message) {
        buildPlanOpen = false;
        builtDomain = null;
        graphAwaitingReview = null;
        if (buildActionSelected) actions.selectAction(buildAction);
        actions.clearResults(buildAction);
        buildStatus.setText(message.isBlank() ? buildPlanPrompt() : message);
        var start = new JPanel(new FlowLayout(FlowLayout.LEFT));
        start.add(planBuild);
        start.add(editBuild);
        start.add(buildStatus);
        buildDetail.removeAll();
        buildDetail.add(start, BorderLayout.NORTH);
        buildDetail.revalidate();
        buildDetail.repaint();
    }

    private String buildPlanPrompt() { return "Plan the saved build of " + project.modelFile().getPath(); }

    private boolean buildReady(String activity) {
        if (busy() || !configuration.resolveUnappliedEdits(activity)) return false;
        if (!project.unsavedModelChanges().isEmpty()) {
            resetBuild("Use " + saveVerb + " to write " + savePaths() + " before building.");
            return false;
        }
        return true;
    }

    private void planBuild() {
        if (!buildReady("planning the build")) return;
        var sessionGuard = project.saveGuard();
        if (sessionGuard == null) {
            resetBuild("Open a saved project to build it.");
            return;
        }
        try {
            var guard = sessionGuard.copy();
            var modelFile = project.modelFile();
            var storage = guard.storage();
            var saved = new GeneratedProjectModelStore().load(modelFile);
            guard.check();
            if (saved.buildOperations().isEmpty()) {
                resetBuild("No build steps in " + modelFile.getName() + ". Use Edit build steps, then " + saveVerb + ".");
                return;
            }
            var snapshot = storage.snapshotFile(saved.name());
            var action = BuildProjectWorkflow.action(saved, modelFile, storage, guard,
                    "Load " + modelFile.getName() + " and " + snapshot.getName(), say -> {
                        say.accept("Reading " + modelFile.getPath() + " and " + snapshot.getPath());
                        guard.check();
                        var loaded = readInstances(modelFile, snapshot, storage, say);
                        guard.check();
                        return new BuildProjectWorkflow.Loaded<>(loaded, loaded.domain().memberRoots().size()
                                + " saved instances loaded from " + snapshot.getPath());
                    }, this::installBuiltDomain,
                    "The open project has been refreshed. Select Instances to inspect it.",
                    "For a graph in this project, use Review graph decisions, save the decisions, "
                            + "and build again. For a dependency, open that project to review its graph.",
                    report -> {
                        if (report.outcome() != ProjectBuild.State.AWAITING_DECISION) return;
                        sessionGuard.acceptWritesFrom(guard);
                        graphReview.reset(project);
                        graphReview.select(selectedClass);
                        graphAwaitingReview = report.steps().stream()
                                .filter(step -> step.action() == ProjectBuild.Action.STOPPED)
                                .map(step -> step.planned().operation())
                                .filter(step -> step.kind() == BuildOperation.Kind.APPLY_GRAPH_DECISIONS)
                                .map(step -> step.target(project.projectModel()))
                                .filter(Objects::nonNull).findFirst().orElse(null);
                    });
            buildPlanOpen = true;
            SwingProcessWorkflow.embed(buildRunner, action, new SwingProcessWorkflow.Embedded(
                    buildDetail, () -> {
                        if (!buildPlanOpen || !buildReady("running the build")) return false;
                        buildPlanOpen = false;
                        queries.logs().registerPipeline("Build project — " + saved.name(), action.pipeline(),
                                saved.name(), snapshot.getAbsoluteFile().getParentFile().toPath());
                        return true;
                    }, () -> resetBuild(""), WorkbenchResultViews::new, this::publishBuildResults));
        } catch (Exception failure) {
            resetBuild("Could not plan build: " + failure.getMessage());
        }
    }

    /** What Load instances will read, or why it cannot read it now. */
    private void showLoadInstancesReadiness() {
        File model = project.modelFile();
        File snapshot = project.snapshotFile();
        String loaded = shownInstances == null ? ""
                : shownInstances.memberRoots().size() + " saved instances loaded from "
                        + snapshot.getPath() + ". Show instances opens them; Load instances "
                        + "reads them again.\n";
        loadInstancesStatus.setText(loaded + (!project.unsavedModelChanges().isEmpty()
                ? "Use " + saveVerb + " to write " + savePaths() + " before loading instances."
                : !snapshot.isFile() ? "No saved instances in " + snapshot.getPath() + ". Build the project first."
                : !model.isFile() ? "No saved model at " + model.getPath() + "."
                : "Reads " + loadInstancePaths(model, snapshot) + "."));
    }

    private static String loadInstancePaths(File model, File snapshot) {
        return model.getPath() + " and " + snapshot.getPath() + ", with "
                + ManualCuration.fileForSnapshot(snapshot).getPath() + " and "
                + CorrectionsSidecar.beside(snapshot, ".autofix.json").getPath() + " when present";
    }

    /**
     * Opens the project's saved instances again through its saved model and curation: the
     * same reading as opening the project and as the last step of a build, without the
     * build. A field added and saved since the last build becomes usable this way.
     */
    private void loadSavedInstances() {
        if (busy() || !configuration.resolveUnappliedEdits("loading instances")) return;
        File model = project.modelFile();
        File snapshot = project.snapshotFile();
        if (!project.unsavedModelChanges().isEmpty() || !snapshot.isFile() || !model.isFile()) {
            showLoadInstancesReadiness();
            return;
        }
        var storage = project.saveGuard() == null ? null : project.saveGuard().storage();
        if (storage == null) {
            loadInstancesStatus.setText("Open a saved project to load its instances.");
            return;
        }
        loadingInstances = true;
        configuration.setEditingEnabled(false);
        loadInstancesStatus.setText("Loading " + loadInstancePaths(model, snapshot) + "…");
        queries.runner().runOnCompletion(new LoadInstances(model, snapshot, storage, queries::info),
                result -> {
                    installLoadedDomain(result);
                    var loaded = result.domain();
                    if (buildPlanOpen) resetBuild("Saved instances reloaded. Plan the build again.");
                    loadInstancesStatus.setText(loaded.memberRoots().size()
                            + " saved instances loaded from " + snapshot.getPath() + ".");
                    updateInstances(loaded, true);
                    finishLoadingInstances();
                },
                failure -> {
                    loadInstancesStatus.setText("Could not load " + snapshot.getPath() + ": "
                            + failure.getMessage());
                    finishLoadingInstances();
                },
                () -> {
                    loadInstancesStatus.setText("Cancelled loading " + snapshot.getPath() + ".");
                    finishLoadingInstances();
                });
        updateSave();
    }

    private void finishLoadingInstances() {
        loadingInstances = false;
        configuration.setEditingEnabled(true);
        updateSave();
    }

    /** New instances are in {@link #instancesView()}: its window takes {@code title}, and
     *  opens when {@code reveal} is set. A build only refreshes it, as in ModelBuilder. */
    public record InstancesShown(String title, boolean reveal) { }

    public QueryObjectResultPanel instancesView() { return instancesView; }

    public void onInstancesShown(Consumer<InstancesShown> listener) {
        instancesShown = listener == null ? ignored -> { } : listener;
    }

    private void updateInstances(DomainModel instances, boolean reveal) {
        shownInstances = instances;
        showLoadedInstances.setEnabled(true);
        var model = project.projectModel();
        ProjectInstancesPresentation.showSaved(instancesView, model, instances, graphReview.annotations());
        revealInstances(reveal);
    }

    private void revealInstances(boolean reveal) {
        if (shownInstances == null) return;
        instancesShown.accept(new InstancesShown(
                "Saved instances (" + shownInstances.memberRoots().size() + ") — " + project.snapshotFile().getName(),
                reveal));
    }

    private record LoadedInstances(DomainModel domain, GraphResults graphs) { }

    private static LoadedInstances readInstances(File model, File snapshot, DomainStorage storage,
            Consumer<String> report) throws Exception {
        var loaded = DomainCatalog.openProject(snapshot, model, storage);
        var backing = loaded.capability(ProjectBacking.class);
        var graphs = new GraphResults(backing.projectModel(), storage);
        graphs.restore(loaded.instances().stream().filter(WikidataDynamicObject.class::isInstance)
                .map(WikidataDynamicObject.class::cast).toList(), report);
        backing.saveGuard().check();
        return new LoadedInstances(loaded, graphs);
    }

    private record LoadInstances(File model, File snapshot, DomainStorage storage, Consumer<String> report)
            implements Query<LoadedInstances> {
        @Override public String purpose() { return "Load instances"; }
        @Override public String skeleton() {
            return "Read the saved instances through the saved model and curation";
        }
        @Override public Map<String, String> parameters() {
            return Map.of("Model", model.getPath(), "Instances", snapshot.getPath(),
                    "Curation", ManualCuration.fileForSnapshot(snapshot).getPath(),
                    "Auto fixes", CorrectionsSidecar.beside(snapshot, ".autofix.json").getPath());
        }
        @Override public LoadedInstances execute(QueryContext context) throws Exception {
            context.cancellation().throwIfCancelled();
            var loaded = readInstances(model, snapshot, storage, report);
            context.cancellation().throwIfCancelled();
            return loaded;
        }
        @Override public int rowCount(LoadedInstances result) { return result.domain().memberRoots().size(); }
        @Override public String summary(LoadedInstances result) {
            return rowCount(result) + " saved instances loaded from " + snapshot.getPath();
        }
    }

    private void installBuiltDomain(LoadedInstances loaded) {
        installLoadedDomain(loaded);
        configuration.setEditingEnabled(false);
        builtDomain = loaded.domain();
    }

    /** Makes a freshly read project the open one, keeping the reader's place in it. */
    private void installLoadedDomain(LoadedInstances result) {
        var loaded = result.domain();
        var loadedProject = Objects.requireNonNull(loaded.capability(ProjectBacking.class),
                "The loaded instances have no saved project model.");
        boolean showingProject = configuration.selectedConstruct() == project.projectModel();
        var activeClass = selectedField == null ? selectedClass : project.projectModel().declaringClass(selectedField);
        String activeField = selectedField == null ? null : selectedField.name();
        configuration.close();
        domain = loaded;
        project = loadedProject;
        graphReview.reset(project, result.graphs());
        transform.replaceLoadedDomain(loaded);
        discardFilterGroups();
        if (filterGroupsSelected) showFilterGroups();
        discovery.refreshInstances();
        valuePanel = null;
        evidenceTarget = null;
        selectedField = null;
        selectedClass = null;
        resetGraphRun("");
        acquisitionTargetCurrent = false;
        acquisitionValues.removeAll();
        compilePlan();
        refreshEvidence();
        configuration = projectConfiguration();
        if (showingProject) {
            configuration.selectConstruct(project.projectModel());
        } else if (activeClass != null) {
            var restored = project.projectModel().resolveClass(activeClass.declarationId(), activeClass.className());
            if (restored != null) {
                Object target = restored.fields().stream().filter(field -> field.name().equals(activeField))
                        .findFirst().<Object>map(field -> field).orElse(restored);
                configuration.selectConstruct(target);
            }
        }
        if (loadActionSelected) refreshAcquisition();
        int divider = workspace.getDividerLocation();
        workspace.setTopComponent(configuration);
        workspace.setDividerLocation(divider);
    }

    private void publishBuildResults(JTabbedPane results) {
        var tabs = (WorkbenchResultViews) results;
        if (graphAwaitingReview != null) {
            var graph = graphAwaitingReview;
            String graphId = graph.declarationId();
            var detail = new JPanel(new FlowLayout(FlowLayout.LEFT));
            var review = new JButton("Review " + graph.className());
            var annotation = new JLabel("Load " + graphReview.annotationFile(graph).getPath());
            review.addActionListener(event -> {
                if (busy()) return;
                var current = project.projectModel().findClassById(graphId);
                if (current == null || current.graphSource() == null) {
                    annotation.setText("The saved model no longer declares the graph " + graph.className() + ".");
                    review.setEnabled(false);
                    return;
                }
                configuration.selectConstruct(current);
                if (configuration.selectedConstruct() != current) return;
                actions.selectAction(graphReviewAction);
                graphReview.loadAnnotations();
            });
            detail.add(review);
            detail.add(annotation);
            tabs.addTab("Graph decisions", detail);
        }
        if (builtDomain != null) {
            var built = builtDomain;
            updateInstances(built, false);
            var detail = new JPanel(new FlowLayout(FlowLayout.LEFT));
            var show = new JButton("Show instances");
            show.addActionListener(event -> revealInstances(true));
            detail.add(show);
            detail.add(new JLabel(built.memberRoots().size() + " saved instances in "
                    + project.snapshotFile().getPath()));
            tabs.addTab("Instances (" + built.memberRoots().size() + ")", detail);
        }
        actions.publishResults(buildAction, tabs, 0, buildActionSelected);
    }

    private boolean busy() {
        return projectBusy() || discovery.selection().isRunning();
    }

    private boolean projectBusy() {
        return saving || loadingValues || loadingInstances || runningEvidence != null || previewing || building
                || graphReview.isLoading() || runningGraph || queries.runner().isRunning();
    }

    private void constructSelected(Object construct) {
        var clazz = construct instanceof GeneratedClassModel value ? value : null;
        if (selectedClass != clazz) {
            selectedClass = clazz;
            membershipDiscovery.showClass(project.projectModel(), clazz);
            refreshPreview();
            graphReview.select(clazz);
            resetGraphRun("");
        }
        fieldSelected(construct instanceof GeneratedFieldModel field ? field : null);
    }

    private void showFilterGroups() {
        if (filterGroups == null) {
            filterGroups = new FilterGroupsPanel(transform,
                    () -> !busy() && configuration.resolveUnappliedEdits("creating a transformed class"),
                    this::transformedClassCreated);
            filterGroupsDetail.add(filterGroups, BorderLayout.CENTER);
        }
        filterGroups.editingEnabled(!busy());
    }

    private void discardFilterGroups() {
        if (filterGroups == null) return;
        filterGroups.close();
        filterGroupsDetail.removeAll();
        filterGroups = null;
    }

    private void transformedClassCreated(GeneratedClassModel output) {
        configuration.showNewConstruct(output);
        if (buildPlanOpen) resetBuild("");
        updateSave();
    }

    private void refreshPreview() {
        clearPreviewResults();
        previewInput = TransformationExecution.inputClass(project.projectModel(), selectedClass);
        previewStatus.setText(previewInput == null ? "Select a class with a configured transformation."
                : "Check loaded " + previewInput.className() + " instances for " + selectedClass.className() + ".");
        updateSave();
    }

    private void clearPreviewResults() {
        if (previewResults.getTabCount() == 0) return;
        if (previewActionSelected) actions.selectAction(previewAction);
        actions.clearResults(previewAction);
        previewResults.removeAll();
    }

    private void previewPopulation() {
        if (previewInput == null || busy() || queries.runner().isRunning()
                || !configuration.resolveUnappliedEdits("previewing the population")) return;
        clearPreviewResults();
        var input = previewInput;
        var output = selectedClass;
        var query = new PreviewPopulation(project.projectModel(), input, output, domain);
        previewing = true;
        previewCompleted = false;
        configuration.setEditingEnabled(false);
        previewStatus.setText("Checking loaded " + input.className() + " instances for " + output.className() + "…");
        updateSave();
        queries.runner().run(query, result -> javax.swing.SwingUtilities.invokeLater(() -> {
            if (previewing) showPreview(input.className(), result);
        }), failure -> {
            previewCompleted = true;
            previewStatus.setText("Population preview failed: " + failure.getMessage());
        });
    }

    private void showPreview(String input, TransformationExecution.Preview result) {
        previewCompleted = true;
        var view = FilterGroupsPanel.instanceCards(transform, input, result.members(), null);
        previewStatus.setText(result.members().size() + " of " + result.examined()
                + " loaded " + input + " instances match.");
        previewResults.addTab("Matching instances (" + result.members().size() + ")", view);
        actions.publishResults(previewAction, previewResults, 0, previewActionSelected);
    }

    private void finishPreview() {
        previewing = false;
        if (!previewCompleted) previewStatus.setText("Population preview cancelled.");
        configuration.setEditingEnabled(true);
        updateSave();
    }

    private record PreviewPopulation(GeneratedProjectModel model, GeneratedClassModel input,
            GeneratedClassModel output, DomainModel domain) implements Query<TransformationExecution.Preview> {
        @Override public String purpose() { return "Preview population for " + output.className(); }
        @Override public String skeleton() { return "Apply the configured filters to loaded instances"; }
        @Override public Map<String, String> parameters() {
            return Map.of("Input class", input.className(), "Output class", output.className());
        }
        @Override public TransformationExecution.Preview execute(QueryContext context) {
            context.cancellation().throwIfCancelled();
            return TransformationExecution.preview(model, output, domain.instances());
        }
        @Override public int rowCount(TransformationExecution.Preview result) { return result.members().size(); }
        @Override public String summary(TransformationExecution.Preview result) {
            return rowCount(result) + " of " + result.examined() + " loaded instances match";
        }
    }

    private void fieldSelected(GeneratedFieldModel field) {
        if (selectedField != field) acquisitionTargetCurrent = false;
        selectedField = field;
        showPlan(field);
        refreshEvidence();
        if (loadActionSelected) refreshAcquisition();
    }

    private void refreshEvidence() {
        var target = new EvidenceTarget(selectedField, selectedField == null ? null : selectedField.definition(),
                FieldSourceBindings.bindings(selectedField, SourceBindingSlot.CANDIDATE_EVIDENCE));
        if (target.equals(evidenceTarget)) return;
        evidenceTarget = target;
        var owner = project.projectModel().declaringClass(selectedField);
        if (owner == null) {
            evidenceActions.show(null, "", List.of(), "Select a field to see its configured evidence recipes.");
            return;
        }
        String field = owner.className() + "." + OwnedFieldSources.declaredFieldPath(owner, selectedField);
        evidenceActions.show(target.definition(), field, target.recipes(),
                "No configured evidence recipes for " + field + ".");
    }

    private JComponent evidencePanel(SourceBinding recipe) {
        var target = evidenceTarget;
        var field = target.field();
        var owner = project.projectModel().declaringClass(field);
        String path = OwnedFieldSources.declaredFieldPath(owner, field);
        if (!loadedFieldMatches(owner.className(), path, field)) {
            var detail = new JPanel(new BorderLayout());
            detail.add(new JLabel(owner.className() + "." + path
                    + " needs instances built with its current field definition."), BorderLayout.NORTH);
            return detail;
        }
        var panel = new ValidationPanel(domain, null, queries.runner(), null, null, WorkbenchPurpose.DATASOURCE);
        panel.useConfiguredEvidenceTarget(owner.className(), path, "Select cards to limit the search",
                recipe, () -> {
                    if (busy() || !configuration.resolveUnappliedEdits("finding field evidence")) return false;
                    if (target == evidenceTarget) return true;
                    // Resolving the draft rebuilt the recipe list. The same recipe on the same,
                    // unchanged field keeps its panel, so the search continues there.
                    var current = evidenceTarget;
                    if (current != null && current.field() == target.field()
                            && Objects.equals(current.definition(), target.definition())
                            && current.recipes().contains(recipe) && evidenceActions.reveal(recipe)) {
                        return true;
                    }
                    Dialogs.showMessage(this, "This evidence recipe or its field changed. Select the recipe again.",
                            "Find configured evidence", JOptionPane.INFORMATION_MESSAGE);
                    return false;
                }, running -> {
                    runningEvidence = running ? panel : null;
                    configuration.setEditingEnabled(!running);
                    updateSave();
                });
        return panel;
    }

    private boolean loadedFieldMatches(String type, String path, GeneratedFieldModel field) {
        var resolved = DomainSchemas.resolve(domain, type, path);
        var declared = FieldDefinitions.toFieldRef(field.definition());
        return resolved != null && resolved.valueKind() == declared.valueKind()
                && (resolved.valueKind() != objectview.field.FieldKind.ORDERED
                        || FieldDefinitions.fromFieldRef(resolved).type() == field.type())
                && resolved.collection() == declared.collection()
                && Objects.equals(resolved.targetType(), declared.targetType());
    }

    private void refreshAcquisition() {
        if (loadingValues || acquisitionTargetCurrent) return;
        acquisitionTargetCurrent = true;
        acquisitionValues.removeAll();
        var owner = project.projectModel().declaringClass(selectedField);
        if (owner == null) {
            acquisitionValues.add(new JLabel("Select a configured field to load its values."),
                    BorderLayout.NORTH);
        } else {
            String path = OwnedFieldSources.declaredFieldPath(owner, selectedField);
            if (selectedFieldPlan == null) {
                acquisitionValues.add(new JLabel("Review the field acquisition plan before loading values."),
                        BorderLayout.NORTH);
            } else if (!loadedFieldMatches(owner.className(), path, selectedField)) {
                acquisitionValues.add(new JLabel(owner.className() + "." + path
                        + " needs instances built with its current field definition."),
                        BorderLayout.NORTH);
            } else {
                if (valuePanel == null) {
                    valuePanel = new ValidationPanel(domain, null, queries.runner(),
                            null, null, WorkbenchPurpose.DATASOURCE);
                }
                valuePanel.useConfiguredValueTarget(owner.className(), path, project.projectModel(),
                        selectedFieldPlan, planText.getText(), this::prepareAcquisition,
                        this::acquisitionRunning);
                acquisitionValues.add(valuePanel, BorderLayout.CENTER);
            }
        }
        acquisitionValues.revalidate();
        acquisitionValues.repaint();
    }

    private boolean prepareAcquisition() {
        return !busy() && configuration.resolveUnappliedEdits("loading values")
                && valuePanel.getParent() == acquisitionValues;
    }

    private void acquisitionRunning(boolean running) {
        loadingValues = running;
        configuration.setEditingEnabled(!running);
        updateSave();
    }

    private void compilePlan() {
        plan = null;
        planProblem = null;
        try {
            plan = ModelSourceExecutionPlan.compileStored(project.projectModel(), datasources);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            planProblem = invalid.getMessage();
        }
    }

    private void showPlan(GeneratedFieldModel field) {
        selectedFieldPlan = null;
        GeneratedProjectModel model = project.projectModel();
        var owner = model.declaringClass(field);
        if (owner == null) {
            planText.setText("Select a field to see its acquisition plan.");
            return;
        }
        String path = OwnedFieldSources.declaredFieldPath(owner, field);
        var text = new StringBuilder(owner.className()).append('.').append(path).append("\n\n");
        if (plan == null) {
            text.append("Could not prepare the datasource plan.\n").append(planProblem);
        } else {
            selectedFieldPlan = plan.fieldValues(owner.className(), path);
            var steps = selectedFieldPlan.steps();
            for (var step : steps) {
                text.append(step.prepared().familyName()).append('\n');
                if (step.target().contextual()) {
                    text.append("When produced by ").append(step.target().contextClassName())
                            .append('.').append(step.target().contextFieldPath()).append('\n');
                }
                String description = step.prepared().description();
                if (!description.equals(step.prepared().familyName())) text.append(description).append('\n');
                for (var element : step.operation().configurationElements(step.binding())) {
                    appendConfiguration(text, element, "  ");
                }
                text.append('\n');
            }
            if (steps.isEmpty()) text.append("This field has no datasource binding.");
        }
        planText.setText(text.toString().stripTrailing());
        planText.setCaretPosition(0);
    }

    private static void appendConfiguration(StringBuilder text, ConfigurationElement element,
            String indent) {
        text.append(indent).append(element.caption()).append('\n');
        for (var child : element.children()) appendConfiguration(text, child, indent + "  ");
    }

    private void fieldApplied(GeneratedFieldModel field) {
        var owner = project.projectModel().declaringClass(field);
        String description = "Field configuration " + owner.className() + "."
                + OwnedFieldSources.declaredFieldPath(owner, field);
        project.recordModelChange(description);
        compilePlan();
        acquisitionTargetCurrent = false;
        fieldSelected(field);
        updateSave();
    }

    /** Chooses among complete actions; a test may answer instead of the dialog. */
    @FunctionalInterface
    interface Choice { int ask(String title, String message, String[] options, int defaultOption); }

    private Choice choice = (title, message, options, defaultOption) ->
            Dialogs.choose(this, title, message, options, defaultOption);

    void choice(Choice value) { choice = value; }

    private java.util.function.Predicate<String> confirmPersistence = message ->
            Dialogs.confirmPersistence(this, verb(), message);

    private String verb() { return saveVerb; }

    private String savePaths() {
        var paths = new StringBuilder(project.modelFile().getPath());
        graphReview.editedFiles().forEach(file -> paths.append('\n').append(file.getPath()));
        return paths.toString();
    }

    /** Lets a test confirm the shared persistence question instead of the dialog. */
    void persistenceConfirmation(java.util.function.Predicate<String> confirm) {
        confirmPersistence = confirm;
    }

    /**
     * Save is one complete action (#535): unapplied edits are resolved first, so it never
     * writes something other than what the form shows.
     */
    private void confirmSave() {
        if (busy()) return;
        boolean draft = configuration.hasUnappliedEdits();
        boolean applyDraft = false;
        if (draft) {
            int answer = choice.ask("Unapplied edits", configuration.editedConstructName()
                    + " has edits that are not applied.\n\nSave writes "
                    + savePaths() + ".",
                    new String[] {"Apply and save", "Discard draft and save applied changes",
                            "Stay"}, 2);
            if (answer == 2) return;
            applyDraft = answer == 0;
        }
        if (!applyDraft && project.unsavedModelChanges().isEmpty()) {
            if (draft) configuration.discardUnappliedEdits();
            saveStatus.setText("No applied changes to save.");
            return;
        }
        // Cancelling the file confirmation keeps the draft: nothing is applied or
        // discarded until the save is confirmed.
        if (!confirmPersistence.test("Save the applied project changes to\n" + savePaths())) return;
        if (applyDraft) {
            // A refused Apply stops here with the draft open and its reason shown.
            if (!configuration.applyUnappliedEdits()) return;
        } else if (draft) {
            configuration.discardUnappliedEdits();
        }
        startSave(null);
    }

    private java.util.function.Consumer<String> saveFailure = message ->
            Dialogs.showMessage(this, message, "Save failed", JOptionPane.ERROR_MESSAGE);

    /** Lets a test receive a save failure instead of the modal message. */
    void saveFailure(java.util.function.Consumer<String> report) { saveFailure = report; }

    private boolean showSaveSuccess = true;

    /** Tests skip the modal success message. */
    void showSaveSuccess(boolean show) { showSaveSuccess = show; }

    boolean saving() { return saving; }

    private void startSave(Runnable afterSuccess) {
        String savedPaths = savePaths();
        boolean savingGraphDecisions = !graphReview.editedFiles().isEmpty();
        saving = true;
        // Keep the reader's place in view; only block input while the file is written.
        blockInput(true);
        saveStatus.setText("Saving " + project.modelFile().getName() + "…");
        updateSave();
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                saveProject();
                return null;
            }

            @Override protected void done() {
                boolean saved = false;
                try {
                    get();
                    saved = true;
                    graphReview.saved();
                    if (buildPlanOpen || savingGraphDecisions) resetBuild("");
                    else buildStatus.setText(buildPlanPrompt());
                    if (afterSuccess == null && showSaveSuccess) {
                        Dialogs.showPersistenceSuccess(UnifiedWorkbenchPanel.this, saveVerb,
                                project.projectName(), savedPaths);
                    }
                } catch (Exception failure) {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    // The applied changes stay in memory; only the write failed.
                    saveFailure.accept(cause.getMessage());
                } finally {
                    blockInput(false);
                    saving = false;
                    updateSave();
                }
                if (saved && afterSuccess != null) afterSuccess.run();
            }
        }.execute();
    }

    void saveProject() throws Exception {
        if (graphReview.editedFiles().isEmpty()) {
            project.writeProjectModel();
        } else {
            project.saveProject(() -> { }, () -> {
                graphReview.writeEdits();
                return null;
            });
        }
    }

    private void updateSave() {
        discovery.selection().hostBusy(projectBusy());
        boolean dirty = !project.unsavedModelChanges().isEmpty();
        // Enabled whenever not saving: unapplied edits can be the only change, and the
        // form cannot announce every keystroke. Save itself says when there is nothing.
        save.setEnabled(!busy());
        preview.setEnabled(previewInput != null && !busy());
        if (filterGroups != null) filterGroups.editingEnabled(!busy());
        cancelPreview.setEnabled(previewing && queries.runner().isRunning());
        planBuild.setEnabled(!busy());
        loadInstances.setEnabled(!busy());
        cancelLoadInstances.setEnabled(loadingInstances && queries.runner().isRunning());
        editBuild.setEnabled(!busy());
        planGraph.setEnabled(!busy() && canRunGraph());
        graphReview.setReviewEnabled(!busy());
        membershipDiscovery.setDiscoveryEnabled(!busy());
        if (loadingInstances) {
            saveStatus.setText("Loading instances…");
        } else if (loadingValues) {
            saveStatus.setText("Loading and reviewing field values");
        } else if (runningEvidence != null) {
            saveStatus.setText("Finding and reviewing configured field evidence");
        } else if (previewing) {
            saveStatus.setText("Previewing population for " + selectedClass.className());
        } else if (building) {
            saveStatus.setText("Building " + project.projectName());
        } else if (runningGraph) {
            saveStatus.setText("Running " + selectedClass.className());
        } else if (graphReview.isLoading()) {
            saveStatus.setText("Loading graph annotations");
        } else if (queries.runner().isRunning()) {
            saveStatus.setText("Finding membership types");
        } else if (!saving) {
            saveStatus.setText(graphReview.editedFiles().isEmpty()
                    ? dirty ? "Applied configuration has unsaved changes" : " "
                    : "Graph annotations have unsaved changes");
        }
    }

    private void blockInput(boolean block) {
        JRootPane root = getRootPane();
        if (root == null) return;
        Component glass = root.getGlassPane();
        if (block && glass.getMouseListeners().length == 0) {
            glass.addMouseListener(new java.awt.event.MouseAdapter() { });
            glass.addKeyListener(new java.awt.event.KeyAdapter() { });
        }
        glass.setCursor(block ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
        glass.setVisible(block);
        if (block) glass.requestFocusInWindow();
    }

    /** Lets a test answer the unapplied-edits question instead of the shared dialog. */
    void unappliedDecision(java.util.function.BiFunction<String, String,
            process.swing.Dialogs.UnappliedEdits> decision) {
        configuration.unappliedDecision(decision);
    }

    /** Whether the open editor holds unapplied edits. */
    boolean hasUnappliedEdits() { return configuration.hasUnappliedEdits(); }

    /**
     * Close is one decision among complete actions (#535): with unapplied edits, Apply,
     * save and close / Discard changes and close / Stay; with only applied unsaved
     * changes, Save and close / Discard changes and close / Stay. Stay is the default.
     * A refused Apply or a failed save keeps the window and the edits open.
     */
    public void requestClose(Runnable closeWindow) {
        if (saving || graphReview.isLoading()) return;
        if (!discovery.requestStopRun(this)) return;
        if (building) {
            SwingCancellationConfirmation.requestForVisibleRun(this, buildRunner::cancel);
            return;
        }
        if (runningGraph) {
            SwingCancellationConfirmation.requestForVisibleRun(this, graphRunner::cancel);
            return;
        }
        if (queries.runner().isRunning() || previewing || loadingInstances) {
            SwingCancellationConfirmation.requestForVisibleRun(this, queries.runner()::cancel);
            return;
        }
        if (loadingValues) {
            SwingCancellationConfirmation.requestForVisibleRun(this, valuePanel::cancelFindData);
            return;
        }
        if (runningEvidence != null) {
            SwingCancellationConfirmation.requestForVisibleRun(this, runningEvidence::cancelFindData);
            return;
        }
        boolean draft = configuration.hasUnappliedEdits();
        boolean unsaved = !project.unsavedModelChanges().isEmpty();
        if (!draft && !unsaved) {
            closeWindow.run();
            return;
        }
        String file = savePaths();
        String message = draft
                ? configuration.editedConstructName() + " has edits that are not applied"
                        + (unsaved ? ", and applied changes are not saved" : "")
                        + ".\n\nSave writes " + file + "."
                : "The applied changes to " + file + " are not saved.";
        String[] options = {draft ? "Apply, save and close" : "Save and close",
                "Discard changes and close", "Stay"};
        int answer = choice.ask("Unsaved project changes", message, options, 2);
        if (answer == 1) {
            closeWindow.run();
        } else if (answer == 0) {
            if (draft && !configuration.applyUnappliedEdits()) return;
            if (project.unsavedModelChanges().isEmpty()) {
                closeWindow.run();
                return;
            }
            startSave(closeWindow);
        }
    }

    @Override public void close() {
        discardFilterGroups();
        discovery.close();
        configuration.close();
        queries.logs().resetForProjectSwitch();
    }
}
