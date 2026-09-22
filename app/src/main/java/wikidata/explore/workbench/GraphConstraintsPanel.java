package wikidata.explore.workbench;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.*;
import datasource.graph.execution.GraphDiscoveryExecutor;
import graphview.GraphViewModel;
import objectview.Viewable;
import process.ProcessOutcome;
import process.ProcessWorkflowPipeline;
import process.QuerySubprocess;
import process.swing.SwingProcessRunner;
import process.swing.workflow.ProcessWorkflowAction;
import process.swing.workflow.ProcessWorkflowPlan;
import process.swing.workflow.ProcessWorkflowResults;
import process.swing.workflow.SwingProcessWorkflow;
import quiz.transform.DynamicViewable;
import wikidata.WikidataIds;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.*;
import wikidata.explore.query.logical.ConfiguredGraphDiscoveryQuery;
import wikidata.ui.WikidataLinks;
import workbench.SimpleDocumentListener;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** First bounded graph editor: configured-QID start, one property edge and one next node. */
final class GraphConstraintsPanel extends JPanel {
    private static final String PROVIDER = "wikidata";

    private enum TestKind {
        HAS_VALUE("has a value"), REACHES_ENTITY("reaches QID"),
        REACHES_UNDER("reaches QID or anything under it"), HAS_NO_VALUE("has no value");
        private final String label;
        TestKind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private enum DirectionChoice {
        OUT("out", GraphTraversalDirection.OUTGOING),
        IN("in", GraphTraversalDirection.INCOMING);
        private final String label;
        private final GraphTraversalDirection direction;
        DirectionChoice(String label, GraphTraversalDirection direction) {
            this.label = label; this.direction = direction;
        }
        @Override public String toString() { return label; }
    }

    /**
     * What a saved graph does NOT do. Run graph classifies and displays; no class
     * population and no snapshot move merely because the constraint was saved. "Applied" is
     * the word a reader takes for "in effect", so every line that reports a saved
     * graph carries this — the one after Apply most of all, because that is the line
     * shown at the moment the modeller acts. Delete it when generation consumes the
     * graph.
     */
    private static final String RUN_ONLY =
            " Run graph previews the output class; Apply result installs its accepted instances.";

    private final GeneratedProjectModel model;
    /**
     * The GRAPH class being edited. A graph constraint used to be the project's one
     * singleton, so this panel edited "the" graph and named it in a field of its own —
     * which was simultaneously the identity of its annotation set and free text. It is
     * a class now: named through the shared header like every other kind, with a
     * declarationId underneath that a rename does not move, and a project may hold
     * several.
     */
    private GeneratedClassModel clazz;
    private final ClassHeaderEditor header;
    private final ClassIdentityEditor identityEditor = new ClassIdentityEditor();
    private final DisplayNameEditor displayNameEditor = new DisplayNameEditor();
    /** Whether the controls hold THIS model's graph, as opposed to construction
     *  defaults. Read by {@link #applyPendingEdits()}; see there for why it matters. */
    private boolean populated;
    private record StartInput(String className, String populationName, int count) {
        boolean population() { return !populationName.isBlank(); }
        @Override public String toString() {
            return population() ? "Population: " + populationName + " · " + className
                    + " · " + count + " instances"
                    : "Class: " + className + " · " + count + " loaded instances";
        }
    }
    private final JComboBox<StartInput> startInputBox = new JComboBox<>();
    private final JComboBox<GraphDiscoveryConfiguration.NodeUse> startUseBox = useBox();
    private final JLabel startQids = new JLabel();
    private final JTextField edgePidField = new JTextField(8);
    private final JComboBox<DirectionChoice> directionBox = new JComboBox<>(DirectionChoice.values());
    private final JLabel arrowLabel = new JLabel("── property out ──▶", SwingConstants.CENTER);
    private final JComboBox<GraphDiscoveryConfiguration.NodeUse> targetUseBox = useBox();
    private final JComboBox<GeneratedClassModel> targetClassBox = new JComboBox<>();
    private final JTextField evidencePidField = new JTextField(8);
    private final JComboBox<DirectionChoice> evidenceDirectionBox =
            new JComboBox<>(DirectionChoice.values());
    private final DefaultListModel<GraphPath> evidenceModel = new DefaultListModel<>();
    private final JList<GraphPath> evidenceList = new JList<>(evidenceModel);
    private final JTextField testPidField = new JTextField(8);
    private final JComboBox<DirectionChoice> testDirectionBox =
            new JComboBox<>(DirectionChoice.values());
    private final JComboBox<TestKind> testKindBox = new JComboBox<>(TestKind.values());
    private final JTextField testQidField = new JTextField(9);
    /** The relation that generalises the tested entity — P279 for a subclass hierarchy. */
    private final JTextField testViaField = new JTextField(6);
    private final DefaultListModel<GraphNodeCondition> testsModel = new DefaultListModel<>();
    private final JList<GraphNodeCondition> testsList = new JList<>(testsModel);
    private final JComboBox<GraphEvidenceCondition.ReviewDisposition> reviewBox =
            reviewBox();
    private final JLabel status = new JLabel(" ");
    private final JButton run = new JButton("Run graph");
    private SwingProcessRunner runner;
    private boolean runWired;
    private Consumer<Void> afterChange = ignored -> {};
    private BiConsumer<String, String> errorDialog;
    private boolean loading;
    private java.util.function.Supplier<java.util.Collection<? extends Viewable>> loadedInstances =
            java.util.List::of;
    private java.util.function.Supplier<Map<String, wikidata.explore.WikidataProperty>>
            propertyCache = Map::of;
    private Consumer<GraphDiscoveryResultStore.Artifact> graphResultConsumer = ignored -> {};
    /** Completed annotations belong to the graph class that produced them. The stable
     * declaration id keeps that ownership intact while the class is renamed. */
    private final Map<String, GraphDiscoveryResultStore.Artifact> graphResults =
            new LinkedHashMap<>();

    GraphConstraintsPanel(GeneratedProjectModel model) {
        super(new BorderLayout(8, 8));
        this.model = java.util.Objects.requireNonNull(model, "model");
        this.header = new ClassHeaderEditor(() -> this.model);
        errorDialog = (title, message) -> JOptionPane.showMessageDialog(
                this, message, title, JOptionPane.ERROR_MESSAGE);
        buildUi();
        refresh();
    }

    void errorDialog(BiConsumer<String, String> value) {
        errorDialog = value == null ? errorDialog : value;
    }

    void afterChange(Consumer<Void> value) {
        afterChange = value == null ? ignored -> {} : value;
    }

    void setProcessRunner(SwingProcessRunner value) {
        runner = value;
        if (!runWired && runner != null) {
            runWired = true;
            runner.registerRunButton(run);
            run.addActionListener(ignored -> runGraph());
            runner.onRunningChanged(ignored -> SwingUtilities.invokeLater(this::updateRunEnabled));
        }
        updateRunEnabled();
    }

    void loadedInstances(
            java.util.function.Supplier<java.util.Collection<? extends Viewable>> value) {
        loadedInstances = value == null ? java.util.List::of : value;
        refreshStartInputs();
    }

    void propertyCache(
            java.util.function.Supplier<Map<String, wikidata.explore.WikidataProperty>> value) {
        propertyCache = value == null ? Map::of : value;
        evidenceList.repaint();
        testsList.repaint();
        refreshArrow();
    }

    void onGraphResult(Consumer<GraphDiscoveryResultStore.Artifact> value) {
        graphResultConsumer = value == null ? ignored -> {} : value;
    }

    GraphDiscoveryResultStore.Artifact lastGraphResult() {
        return clazz == null ? null : resultOf(clazz);
    }

    /**
     * Every completed annotation set still owned by the class it was run for.
     *
     * <p>Read by the save boundary, which writes each one. A set whose class has since
     * been renamed is dropped here rather than offered: its instances are stamped with
     * the name the class had, so there is no file it could go to that would not be
     * named one thing and typed another.
     */
    List<GraphDiscoveryResultStore.Artifact> graphResults() {
        return model.graphClasses().stream()
                .map(this::resultOf)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** Restore every named graph's annotations from the project's loaded pool. */
    void restoreGraphResults(
            java.util.Collection<WikidataDynamicObject> loadedObjects) {
        for (GeneratedClassModel graphClass : model.graphClasses()) {
            if (resultOf(graphClass) != null || graphClass.graphSource() == null) continue;
            GraphDiscoveryResultStore.Artifact restored = GraphDiscoveryResultStore.restore(
                    model.name(), graphClass.className(),
                    graphClass.graphSource().outputClassName(), loadedObjects);
            if (restored != null) {
                graphResults.put(resultKey(graphClass), restored);
            }
        }
    }

    /**
     * The annotation set held for one graph class, or null once it stops describing it.
     *
     * <p>The map is keyed by declarationId, which is what keeps ownership through a
     * rename — but ownership is not currency. The names the artifact recorded are what
     * its instances are stamped with and what its file is keyed by, so when they stop
     * matching the class, the run is of a class that no longer exists and is forgotten
     * here. Running again replays the adjacency from the local store.
     */
    private GraphDiscoveryResultStore.Artifact resultOf(GeneratedClassModel graphClass) {
        if (graphClass == null) return null;
        String key = resultKey(graphClass);
        GraphDiscoveryResultStore.Artifact held = graphResults.get(key);
        if (held == null) return null;
        if (GraphDiscoveryResultStore.describes(
                held, model.name(), graphClass.className())) {
            return held;
        }
        graphResults.remove(key);
        return null;
    }

    boolean showLastGraphResult() {
        GraphDiscoveryResultStore.Artifact current = lastGraphResult();
        if (runner == null || current == null) return false;
        GraphDiscoveryResultStore.Artifact shown = current;
        ProcessWorkflowAction<GraphDiscoveryResultStore.Artifact,
                GraphDiscoveryResultStore.Artifact> action = new ProcessWorkflowAction<>() {
            @Override public String id() { return "show-graph-result"; }
            @Override public ProcessWorkflowPlan plan() {
                return new ProcessWorkflowPlan("Graph result", "Already completed", List.of());
            }
            @Override public ProcessOutcome<GraphDiscoveryResultStore.Artifact> preparedOutcome() {
                return ProcessOutcome.succeeded(shown, "Last graph run");
            }
            @Override public process.Process<GraphDiscoveryResultStore.Artifact> process() {
                throw new UnsupportedOperationException("Prepared graph result");
            }
            @Override public boolean multipleResultSelection() { return true; }
            @Override public java.util.function.Function<Object, String> valueLinker() {
                return WikidataLinks.valueLinker();
            }
            @Override public ProcessWorkflowResults<GraphDiscoveryResultStore.Artifact> results(
                    ProcessOutcome<GraphDiscoveryResultStore.Artifact> outcome) {
                return graphResults(outcome.result());
            }
            @Override public void apply(List<GraphDiscoveryResultStore.Artifact> decisions) {
                remember(decisions.getFirst());
                graphResultConsumer.accept(decisions.getFirst());
            }
        };
        SwingProcessWorkflow.start(this, runner, action);
        return true;
    }

    private ProcessWorkflowResults<GraphDiscoveryResultStore.Artifact> graphResults(
            GraphDiscoveryResultStore.Artifact artifact) {
        long accepted = artifact.instances().stream()
                .filter(value -> decisionValue(value).contains("Accepted")).count();
        long review = artifact.instances().stream()
                .filter(value -> decisionValue(value).contains("Review")).count();
        long rejected = artifact.instances().stream()
                .filter(value -> decisionValue(value).contains("Rejected")).count();
        String summary = artifact.instances().size() + " reached: " + accepted
                + " accepted, " + review + " review, " + rejected + " rejected. "
                + "Apply result will narrow " + artifact.outputClass() + " in "
                + artifact.projectName() + " to the "
                + artifact.acceptedIdentities().size()
                + " accepted entity(ies), keeping the instances already generated for "
                + "them and dropping the rest.";
        return new ProcessWorkflowResults<>("Run graph — results", summary, "Apply result",
                List.of(artifactTab("All", artifact, null),
                        artifactTab("Accepted", artifact, "Accepted"),
                        artifactTab("Review", artifact, "Review"),
                        artifactTab("Rejected", artifact, "Rejected")),
                () -> artifact, "Close without applying result");
    }

    private void runGraph() {
        if (runner == null || runner.isRunning()) return;
        try {
            clearCompletedPopulation();
            GeneratedProjectModel snapshot = model.copy();
            // The run is named by the class that declares it, so the snapshot's own copy
            // of that class is what the run reads — not the live one an edit could move
            // underneath it.
            GeneratedClassModel snapshotClass = snapshot.findClass(clazz.className());
            ConfiguredGraphDiscoveryQuery query = new ConfiguredGraphDiscoveryQuery(
                    snapshot, snapshotClass, loadedInstances.get());
            ProcessWorkflowPipeline pipeline = new ProcessWorkflowPipeline(List.of(
                    new ProcessWorkflowPipeline.Phase(
                            "discover-graph", "Discover and classify graph nodes",
                            "Follow the configured edge, acquire evidence and classify "
                                    + "each reached entity.",
                            graphDetails(snapshot, snapshotClass))));
            ProcessWorkflowAction<ConfiguredGraphDiscoveryQuery.Result,
                    GraphDiscoveryResultStore.Artifact> action =
                    graphWorkflow(query, pipeline, snapshot, snapshotClass,
                            graphSummary(snapshot, snapshotClass, loadedInstances.get()));
            SwingProcessWorkflow.start(this, runner, action);
        } catch (Exception error) {
            showGraphFailure(error);
        }
    }

    private ProcessWorkflowAction<ConfiguredGraphDiscoveryQuery.Result,
            GraphDiscoveryResultStore.Artifact> graphWorkflow(
            ConfiguredGraphDiscoveryQuery query, ProcessWorkflowPipeline pipeline,
            GeneratedProjectModel snapshot, GeneratedClassModel graphClass,
            DynamicViewable summary) {
        return new ProcessWorkflowAction<>() {
            @Override public String id() { return "discover-graph"; }
            @Override public ProcessWorkflowPipeline pipeline() { return pipeline; }
            @Override public java.util.function.Function<Object, String> valueLinker() {
                return WikidataLinks.valueLinker();
            }
            @Override public boolean multipleResultSelection() { return true; }
            @Override public ProcessWorkflowPlan plan() {
                return new ProcessWorkflowPlan(
                        "Run graph", "Inspect the configured traversal and evidence tests, "
                                + "then explicitly start discovery.",
                        List.of(ProcessWorkflowPlan.Tab.component(
                                        "Graph", () -> graphPlanView(
                                                snapshot, graphClass,
                                                loadedInstances.get()), true),
                                new ProcessWorkflowPlan.Tab(
                                "Scope", List.of(summary)))).withoutPipelineTab();
            }
            @Override public process.Process<ConfiguredGraphDiscoveryQuery.Result> process() {
                return new process.Process<>() {
                    @Override public process.ProcessPlan plan() {
                        return new process.ProcessPlan(
                                query.purpose(), query.description(), query.parameters());
                    }
                    @Override public ProcessOutcome<ConfiguredGraphDiscoveryQuery.Result>
                            execute(process.ProcessContext context) {
                        pipeline.start("discover-graph", query.purpose());
                        ProcessOutcome<ConfiguredGraphDiscoveryQuery.Result> outcome =
                                context.run(new QuerySubprocess<>(query));
                        pipeline.finish(outcome.status(), outcome.summary());
                        return outcome;
                    }
                };
            }
            @Override public ProcessWorkflowResults<GraphDiscoveryResultStore.Artifact> results(
                    ProcessOutcome<ConfiguredGraphDiscoveryQuery.Result> outcome) {
                return graphResults(outcome.result(), snapshot.name(),
                        graphClass.className());
            }
            @Override public void apply(List<GraphDiscoveryResultStore.Artifact> decisions)
                    throws Exception {
                remember(decisions.getFirst());
                graphResultConsumer.accept(decisions.getFirst());
            }
        };
    }

    /** The graph a class declares, or null while it declares none. */
    private static GraphDiscoveryConfiguration configurationOf(GeneratedClassModel graphClass) {
        return graphClass == null || graphClass.graphSource() == null ? null
                : graphClass.graphSource().configurationFor(graphClass.className());
    }

    /** Opens the editor on a GRAPH class, the way every other kind editor is opened. */
    void edit(GeneratedClassModel value) {
        clazz = value;
        header.show(value);
        // The key is fixed, like an aggregate's and an owned class's: a graph annotation
        // is identified by the candidate it classifies, so there is nothing to pick.
        identityEditor.showFixedKey(value);
        displayNameEditor.show(value);
        populated = false;
        refresh();
        populated = clazz != null;
    }

    GeneratedClassModel editing() {
        return clazz;
    }

    private void showGraphFailure(Exception error) {
        String detail = message(error);
        status("Graph discovery failed: " + detail, true);
        errorDialog.accept("Graph discovery failed", detail);
    }

    void refresh() {
        loading = true;
        try {
            refreshClasses();
            GraphDiscoveryConfiguration saved = configurationOf(clazz);
            clearDraftControls();
            refreshStartInputs();
            if (saved == null && startInputBox.getItemCount() > 0) {
                startInputBox.setSelectedIndex(0);
            }
            if (saved != null) {
                selectStart(saved.startNode());
                startUseBox.setSelectedItem(saved.startNode().use());
                if (!saved.nextNodes().isEmpty()) load(saved.nextNodes().getFirst());
            }
            refreshTargetState();
            refreshArrow();
            status(saved == null ? "No discovery graph configured."
                    : saved.nextNodes().isEmpty()
                    ? "Start node saved: " + startInputLabel(model, saved.startNode())
                            + ". Add the property connecting the two nodes to complete"
                            + " the graph."
                    : "Configured." + RUN_ONLY, false);
            if (saved != null) loadSavedGraphResult(saved);
            updateRunEnabled();
            populated = true;
        } finally { loading = false; }
    }

    /** An editor instance serves every graph class. Each selection starts from that
     * class's saved source, never from the controls left by the previously selected one. */
    private void clearDraftControls() {
        edgePidField.setText("");
        directionBox.setSelectedItem(DirectionChoice.OUT);
        evidenceModel.clear();
        testsModel.clear();
        evidencePidField.setText("");
        testPidField.setText("");
        testQidField.setText("");
        testViaField.setText("");
        reviewBox.setSelectedItem(
                GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT);
        if (targetClassBox.getItemCount() > 0) targetClassBox.setSelectedIndex(0);
    }

    private void loadSavedGraphResult(GraphDiscoveryConfiguration saved) {
        if (saved == null || lastGraphResult() != null) return;
        // An unnamed constraint has no annotation set to load; saying so beats looking
        // for a file whose name we would have had to invent.
        if (saved.name().isBlank()) return;
        String outputClass = saved.nextNodes().stream()
                .filter(node -> node.use()
                        == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION)
                .map(GraphDiscoveryConfiguration.NextNode::populationClass)
                .findFirst().orElse("");
        if (outputClass.isBlank()) return;
        try {
            GraphDiscoveryResultStore.Artifact loaded = GraphDiscoveryResultStore.load(
                    model.name(), saved.name(), outputClass);
            if (loaded != null) {
                remember(loaded);
                status("Loaded " + loaded.instances().size()
                        + " saved graph annotations from "
                        + GraphDiscoveryResultStore.destination(
                                model.name(), saved.name()).getPath() + ".", false);
            }
        } catch (Exception error) {
            status("Could not load saved graph annotations from "
                    + GraphDiscoveryResultStore.destination(model.name(), saved.name()).getPath()
                    + ": " + message(error), true);
        }
    }

    /**
     * Flushes the draft the way a save does: the panel contributes what it holds, and
     * nothing at all when it holds nothing.
     *
     * <p>An emptied draft is how a saved graph is REMOVED, and a section that was never
     * opened looks exactly like one: its controls sit at their construction defaults
     * because {@link #refresh()} runs only when the section is selected. Applying such a
     * panel on every save would delete the graph it had never read. Only a panel showing
     * this model's graph can tell an emptied draft from an unread one, so only that panel
     * is flushed.
     */
    void applyPendingEdits() {
        if (!populated) return;
        // A flush persists what the modeller authored; it never CREATES a graph out of
        // the panel's default control state. The start-class combo always carries a
        // selection, so without this every save of a domain that has no graph would
        // quietly store a start node naming whichever class happens to be first.
        // Pressing Apply is what turns a start class into a saved graph; once one
        // exists, a flush keeps it up to date like any other editor.
        if (clazz == null) return;
        if (clazz.graphSource() == null
                && !WikidataIds.isPid(cleanPid(edgePidField.getText()))) {
            return;
        }
        applyEdits();
    }

    /** Detaches the panel from the contents it read, for the same reason the other
     *  editors are detached before a domain is loaded in place: the model instance is
     *  reused, so controls still holding the previous domain's graph would otherwise be
     *  flushed into the new one. */
    void abandon() {
        populated = false;
        graphResults.clear();
    }

    void applyEdits() {
        if (clazz == null) return;
        // The name is the class's, written by the shared header like every other kind's.
        // It was a free-text field here, and a field an editor rewrites cannot also be
        // the identity of the annotation set written under it.
        header.applyEdits();
        displayNameEditor.applyEdits();
        StartInput start = selectedStart();
        if (start == null) return;
        try {
            String pid = cleanPid(edgePidField.getText());
            // Apply only ever SAVES; removing is its own button. The start node is a
            // decision in its own right and is kept as one: a graph is authored in the
            // order it is read, and refusing to record the start until an edge exists
            // discarded a choice the modeller had just made — which is how choosing a
            // start class, pressing Apply and saving still came back as the first class
            // in the list. An edgeless graph traverses nothing, so Run stays disabled
            // and the status says what the graph still needs.
            if (pid.isEmpty() && evidenceModel.isEmpty() && testsModel.isEmpty()) {
                GraphClassSource replacement = new GraphClassSource(
                        new GraphDiscoveryConfiguration.StartNode(
                                start.population() ? "" : start.className(),
                                start.populationName(), use(startUseBox)),
                        List.of());
                boolean changed = !sameSource(clazz.graphSource(), replacement);
                clazz.graphSource(replacement);
                if (changed) clearCompletedPopulation();
                status("Start node saved: " + start
                        + ". Add the property connecting the two nodes to complete"
                        + " the graph.", false);
                updateRunEnabled();
                afterChange.accept(null);
                return;
            }
            if (!WikidataIds.isPid(pid)) {
                throw new IllegalArgumentException("Choose the property connecting the two nodes");
            }
            if (evidenceModel.isEmpty() != testsModel.isEmpty()) {
                throw new IllegalArgumentException(
                        "Add both an evidence relation and an evidence test, or leave both empty");
            }
            GraphEvidenceCondition evidence = evidenceModel.isEmpty() ? null
                    : new GraphEvidenceCondition("Node evidence", elements(evidenceModel),
                            elements(testsModel), reviewDisposition());
            GeneratedClassModel targetClass = selectedClass(targetClassBox);
            if (targetClass == null) {
                throw new IllegalArgumentException(
                        "Choose the single output class produced by this graph constraint");
            }
            var target = new GraphDiscoveryConfiguration.NextNode(
                    new GraphRelation(PROVIDER, pid), direction().direction,
                    GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                    targetClass.className(),
                    evidence);
            GraphClassSource replacement = new GraphClassSource(
                    new GraphDiscoveryConfiguration.StartNode(
                            start.population() ? "" : start.className(),
                            start.populationName(), use(startUseBox)),
                    List.of(target));
            boolean changed = !sameSource(clazz.graphSource(), replacement);
            clazz.graphSource(replacement);
            if (changed) clearCompletedPopulation();
            status("Applied discovery graph." + RUN_ONLY, false);
            updateRunEnabled();
            afterChange.accept(null);
        } catch (IllegalArgumentException ex) { status(ex.getMessage(), true); }
    }

    private void buildUi() {
        startInputBox.setName("graph.startInput");
        startUseBox.setName("graph.startUse");
        startQids.setName("graph.startQids");
        edgePidField.setName("graph.edgeProperty");
        directionBox.setName("graph.edgeDirection");
        arrowLabel.setName("graph.edgeLabel");
        targetUseBox.setName("graph.targetUse");
        targetClassBox.setName("graph.targetClass");
        evidencePidField.setName("graph.evidenceProperty");
        evidenceDirectionBox.setName("graph.evidenceDirection");
        testPidField.setName("graph.testProperty");
        testDirectionBox.setName("graph.testDirection");
        testKindBox.setName("graph.testKind");
        testQidField.setName("graph.testQid");
        testViaField.setName("graph.testVia");
        reviewBox.setName("graph.reviewDisposition");
        // Named so the checked-in layout and the draft-clearing test can see them:
        // what is IN these lists is the draft, and nothing could read it before.
        evidenceList.setName("graph.evidenceList");
        testsList.setName("graph.testsList");
        status.setName("graph.status");
        targetUseBox.setSelectedItem(GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION);
        targetUseBox.setEnabled(false);

        JPanel chain = new JPanel();
        chain.setLayout(new BoxLayout(chain, BoxLayout.X_AXIS));
        chain.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        chain.add(startNodePanel());
        chain.add(Box.createHorizontalStrut(8));
        chain.add(edgePanel());
        chain.add(Box.createHorizontalStrut(8));
        chain.add(nextNodePanel());
        JComponent graph = objectview.utils.swing.ScrollPaneUtils.horizontalOnly(chain);

        // Header, then this kind's own construct, then identity and name — the order
        // every kind editor reads in. The graph IS this kind's triple: what it starts
        // from, the edge it follows, and what the node it reaches produces.
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        header.setAlignmentX(LEFT_ALIGNMENT);
        graph.setAlignmentX(LEFT_ALIGNMENT);
        identityEditor.setAlignmentX(LEFT_ALIGNMENT);
        displayNameEditor.setAlignmentX(LEFT_ALIGNMENT);
        form.add(header);
        form.add(graph);
        form.add(identityEditor);
        form.add(displayNameEditor);
        add(new JScrollPane(form), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clear = new JButton("Clear draft");
        JButton apply = new JButton("Apply graph");
        // No Remove button: a graph constraint is a class, and a class is removed where
        // every other class is removed. A second, kind-specific delete would be a
        // construct's own exception to the one that already exists.
        actions.add(run);
        actions.add(clear); actions.add(apply);
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(actions, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        startInputBox.addActionListener(e -> { if (!loading) refreshStartCount(); });
        edgePidField.getDocument().addDocumentListener(SimpleDocumentListener.of(this::refreshArrow));
        directionBox.addActionListener(e -> refreshArrow());
        targetUseBox.addActionListener(e -> refreshTargetState());
        testKindBox.addActionListener(e -> refreshTestQidState());
        apply.addActionListener(e -> applyEdits());
        clear.addActionListener(e -> {
            evidenceModel.clear();
            testsModel.clear();
            edgePidField.setText("");
            // Clearing the DRAFT says nothing about the saved graph. It used to point at
            // Apply as the way to remove one, which is how a blank draft came to mean
            // "delete" — the act now has its own button and its own name.
            status("Draft cleared.", false);
        });
        refreshTestQidState();
    }

    private JPanel startNodePanel() {
        JPanel panel = nodePanel("Start node");
        addLine(panel, "Start with:", startInputBox);
        addLine(panel, "Usable Wikidata QIDs:", startQids);
        addLine(panel, "Use these entities as:", startUseBox);
        panel.add(new JLabel("A saved population selection is an exact reusable QID set."));
        return panel;
    }

    private JPanel edgePanel() {
        JPanel panel = nodePanel("Incoming edge of next node");
        arrowLabel.setFont(arrowLabel.getFont().deriveFont(Font.BOLD, 15f));
        panel.add(arrowLabel);
        addLine(panel, "Property:", edgePidField);
        addLine(panel, "Direction from previous node:", directionBox);
        return panel;
    }

    private JPanel nextNodePanel() {
        JPanel panel = nodePanel("Next node");
        addLine(panel, "Graph output:", new JLabel("Instances of one configured class"));
        addLine(panel, "Output class:", targetClassBox);
        panel.add(new JLabel("Evidence relations from this node (one or more may reach evidence)"));
        JPanel evidenceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        evidenceRow.add(new JLabel("Property:")); evidenceRow.add(evidencePidField);
        evidenceRow.add(evidenceDirectionBox);
        JButton addEvidence = new JButton("Add evidence relation");
        evidenceRow.add(addEvidence); panel.add(evidenceRow);
        evidenceList.setVisibleRowCount(2);
        evidenceList.setCellRenderer(pathRenderer());
        panel.add(new JScrollPane(evidenceList));
        JButton removeEvidence = new JButton("Remove selected evidence relation");
        removeEvidence.addActionListener(e -> removeSelected(evidenceList, evidenceModel));
        JButton editEvidence = new JButton("Edit selected evidence relation");
        editEvidence.addActionListener(e -> editSelectedEvidence());
        panel.add(editEvidence);
        panel.add(removeEvidence);

        panel.add(new JLabel("Tests on reached evidence entities (one or more may match)"));
        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        testRow.add(new JLabel("Property:")); testRow.add(testPidField);
        testRow.add(testDirectionBox); testRow.add(testKindBox); testRow.add(testQidField);
        testRow.add(new JLabel("via")); testRow.add(testViaField);
        JButton addTest = new JButton("Add evidence test"); testRow.add(addTest);
        panel.add(testRow);
        testsList.setVisibleRowCount(2);
        testsList.setCellRenderer(conditionRenderer());
        panel.add(new JScrollPane(testsList));
        JButton removeTest = new JButton("Remove selected evidence test");
        removeTest.addActionListener(e -> removeSelected(testsList, testsModel));
        JButton editTest = new JButton("Edit selected evidence test");
        editTest.addActionListener(e -> editSelectedTest());
        panel.add(editTest);
        panel.add(removeTest);
        addLine(panel, "Review candidates in output class:", reviewBox);
        addEvidence.addActionListener(e -> addEvidencePath());
        addTest.addActionListener(e -> addTest());
        return panel;
    }

    private static JPanel nodePanel(String title) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.setPreferredSize(new Dimension(470, 520));
        panel.setMaximumSize(new Dimension(560, 650));
        return panel;
    }

    private static void addLine(JPanel panel, String label, JComponent value) {
        JPanel line = new JPanel(new BorderLayout(5, 2));
        line.add(new JLabel(label), BorderLayout.NORTH);
        line.add(value, BorderLayout.CENTER);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
        panel.add(line);
    }

    private void refreshClasses() {
        targetClassBox.removeAllItems();
        for (GeneratedClassModel candidate : model.classes()) {
            if (candidate == null || candidate.isImported()) continue;
            // A graph produces entities, and the annotations about them are what a
            // graph class holds — so no graph class is another graph's output, this
            // one least of all.
            if (candidate.classKind() == ClassKind.GRAPH) continue;
            targetClassBox.addItem(candidate);
        }
    }

    private void refreshStartInputs() {
        boolean previousLoading = loading;
        loading = true;
        try {
            StartInput keep = selectedStart();
            startInputBox.removeAllItems();
            java.util.Collection<? extends Viewable> instances = loadedInstances.get();
            for (GeneratedClassModel candidate : model.classes()) {
                if (candidate == null || candidate.isImported()) continue;
                // A graph class's instances are the annotations a run produced, not
                // entities to traverse from — and this editor's own class would
                // otherwise be offered as its own start.
                if (candidate.classKind() == ClassKind.GRAPH) continue;
                int count = loadedQids(instances, candidate.className()).size();
                startInputBox.addItem(new StartInput(candidate.className(), "", count));
            }
            for (Selection selection : model.selections()) {
                if (selection instanceof PopulationSelection population) {
                    startInputBox.addItem(new StartInput(population.className(),
                            population.name(), population.instanceQids().size()));
                }
            }
            if (keep != null) selectStart(keep);
            refreshStartCount();
        } finally {
            loading = previousLoading;
        }
    }

    private void refreshStartCount() {
        StartInput start = selectedStart();
        int count = start == null ? 0 : start.count();
        startQids.setText(count + (count == 1 ? " QID" : " QIDs"));
    }

    private StartInput selectedStart() {
        return startInputBox.getSelectedItem() instanceof StartInput input ? input : null;
    }

    private void selectStart(GraphDiscoveryConfiguration.StartNode start) {
        selectStart(new StartInput(start.qidSourceClass(), start.populationSelection(), 0));
    }

    private void selectStart(StartInput wanted) {
        for (int i = 0; i < startInputBox.getItemCount(); i++) {
            StartInput candidate = startInputBox.getItemAt(i);
            if (wanted.populationName().equals(candidate.populationName())
                    && (wanted.population() || wanted.className().equals(candidate.className()))) {
                startInputBox.setSelectedIndex(i);
                refreshStartCount();
                return;
            }
        }
    }

    private static List<String> loadedQids(
            java.util.Collection<? extends Viewable> instances, String className) {
        if (instances == null) return List.of();
        return instances.stream()
                .filter(value -> value.directClassNames().contains(className))
                .map(quiz.source.SourceIdentities::wikidataQid)
                .filter(java.util.Objects::nonNull).distinct().toList();
    }

    private void refreshTargetState() {
        targetClassBox.setEnabled(true);
    }

    private void refreshArrow() {
        String pid = cleanPid(edgePidField.getText());
        arrowLabel.setText("── " + (pid.isBlank() ? "property" : labelledPid(pid))
                + " " + direction() + " ──▶");
    }

    private void refreshTestQidState() {
        // Both reaching tests name the entity they look for; only the generalising one
        // also needs the relation that generalises it.
        Object kind = testKindBox.getSelectedItem();
        testQidField.setEnabled(
                kind == TestKind.REACHES_ENTITY || kind == TestKind.REACHES_UNDER);
        testViaField.setEnabled(kind == TestKind.REACHES_UNDER);
    }

    private void addEvidencePath() {
        String pid = cleanPid(evidencePidField.getText());
        if (!WikidataIds.isPid(pid)) {
            status("Enter the property that reaches an evidence entity", true);
            return;
        }
        GraphPath path = GraphPath.direct(new GraphRelation(PROVIDER, pid),
                selectedDirection(evidenceDirectionBox).direction);
        if (!elements(evidenceModel).contains(path)) evidenceModel.addElement(path);
        evidencePidField.setText("");
        status("Draft changed; Apply graph to save it.", false);
    }

    private void editSelectedEvidence() {
        int index = evidenceList.getSelectedIndex();
        if (index < 0) return;
        GraphPath path = evidenceModel.remove(index);
        evidencePidField.setText(path.relation().relationId());
        evidenceDirectionBox.setSelectedItem(path.direction()
                == GraphTraversalDirection.INCOMING ? DirectionChoice.IN : DirectionChoice.OUT);
        status("Edit the property or direction, then press Add evidence relation.", false);
    }

    private void addTest() {
        String pid = cleanPid(testPidField.getText());
        if (!WikidataIds.isPid(pid)) {
            status("Enter the property tested on the evidence entity", true);
            return;
        }
        GraphRelation relation = new GraphRelation(PROVIDER, pid);
        GraphNodeCondition condition;
        TestKind kind = (TestKind) testKindBox.getSelectedItem();
        GraphTraversalDirection direction = selectedDirection(testDirectionBox).direction;
        if (kind == TestKind.REACHES_ENTITY || kind == TestKind.REACHES_UNDER) {
            String qid = cleanQid(testQidField.getText());
            if (!WikidataIds.isQid(qid)) {
                status("Enter the QID the evidence property must reach", true);
                return;
            }
            if (kind == TestKind.REACHES_ENTITY) {
                condition = new GraphRelationReaches(
                        relation, direction, EntityRef.wikidata(qid));
            } else {
                String via = cleanPid(testViaField.getText());
                if (!WikidataIds.isPid(via)) {
                    status("Enter the property that generalises it, for example P279", true);
                    return;
                }
                condition = GraphRelationReachesUnder.of(relation, direction,
                        EntityRef.wikidata(qid), new GraphRelation(PROVIDER, via));
            }
        } else if (kind == TestKind.HAS_NO_VALUE) {
            condition = new GraphRelationAbsent(relation, direction);
        } else condition = new GraphRelationExists(relation, direction);
        if (!elements(testsModel).contains(condition)) testsModel.addElement(condition);
        testPidField.setText(""); testQidField.setText("");
        status("Draft changed; Apply graph to save it.", false);
    }

    private void editSelectedTest() {
        int index = testsList.getSelectedIndex();
        if (index < 0) return;
        GraphNodeCondition condition = testsModel.remove(index);
        GraphRelation relation;
        GraphTraversalDirection direction;
        if (condition instanceof GraphRelationExists value) {
            testKindBox.setSelectedItem(TestKind.HAS_VALUE);
            relation = value.relation(); direction = value.direction();
        } else if (condition instanceof GraphRelationAbsent value) {
            testKindBox.setSelectedItem(TestKind.HAS_NO_VALUE);
            relation = value.relation(); direction = value.direction();
        } else if (condition instanceof GraphRelationReaches value) {
            testKindBox.setSelectedItem(TestKind.REACHES_ENTITY);
            relation = value.relation(); direction = value.direction();
            testQidField.setText(value.entity().id());
        } else {
            status("This condition cannot yet be edited in the form.", true);
            testsModel.add(index, condition);
            return;
        }
        testPidField.setText(relation.relationId());
        testDirectionBox.setSelectedItem(direction == GraphTraversalDirection.INCOMING
                ? DirectionChoice.IN : DirectionChoice.OUT);
        refreshTestQidState();
        status("Edit the condition, then press Add evidence test.", false);
    }

    private void load(GraphDiscoveryConfiguration.NextNode node) {
        edgePidField.setText(node.property().relationId());
        directionBox.setSelectedItem(node.directionFromPrevious() == GraphTraversalDirection.INCOMING
                ? DirectionChoice.IN : DirectionChoice.OUT);
        targetUseBox.setSelectedItem(GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION);
        selectClass(targetClassBox, node.populationClass());
        GraphEvidenceCondition evidence = node.evidenceCondition();
        replace(evidenceModel, evidence == null ? List.of() : evidence.evidencePaths());
        replace(testsModel, evidence == null ? List.of() : evidence.tests());
        reviewBox.setSelectedItem(evidence == null
                ? GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT
                : evidence.reviewDisposition());
    }

    private static JComboBox<GraphDiscoveryConfiguration.NodeUse> useBox() {
        JComboBox<GraphDiscoveryConfiguration.NodeUse> box = new JComboBox<>(GraphDiscoveryConfiguration.NodeUse.values());
        box.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setText(value == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION
                        ? "Members of configured class" : "Intermediate only");
                return this;
            }
        });
        return box;
    }

    private ListCellRenderer<Object> conditionRenderer() {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof GraphRelationExists c) setText(labelledPid(c.relation().relationId()) + " has a value");
                else if (value instanceof GraphRelationAbsent c) setText(labelledPid(c.relation().relationId()) + " has no value");
                else if (value instanceof GraphRelationReaches c) setText(labelledPid(c.relation().relationId()) + " reaches " + labelledQid(c.entity().id()));
                return this;
            }
        };
    }

    private ListCellRenderer<Object> pathRenderer() {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof GraphPath path) {
                    setText(labelledPid(path.relation().relationId()) + " "
                            + directionLabel(path.direction()));
                }
                return this;
            }
        };
    }

    private static JComboBox<GraphEvidenceCondition.ReviewDisposition> reviewBox() {
        var box = new JComboBox<>(GraphEvidenceCondition.ReviewDisposition.values());
        box.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setText(value == GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT
                        ? "Exclude unless manually accepted"
                        : "Include unless manually rejected");
                return this;
            }
        });
        return box;
    }

    private String labelledPid(String pid) {
        wikidata.explore.WikidataProperty property = propertyCache.get().get(pid);
        String label = property == null ? "" : property.label();
        return label == null || label.isBlank() ? pid : label + " (" + pid + ")";
    }

    private String labelledQid(String qid) {
        for (Viewable value : loadedInstances.get()) {
            String found = quiz.source.SourceIdentities.wikidataQid(value);
            if (qid.equalsIgnoreCase(found)) {
                String label = value.getDisplayName();
                if (label != null && !label.isBlank() && !qid.equalsIgnoreCase(label)) {
                    return label + " (" + qid + ")";
                }
            }
        }
        return qid;
    }

    private static String directionLabel(GraphTraversalDirection direction) {
        return direction == GraphTraversalDirection.INCOMING ? "in" : "out";
    }

    private static <T> void removeSelected(JList<T> list, DefaultListModel<T> model) {
        int[] selected = list.getSelectedIndices();
        for (int i = selected.length - 1; i >= 0; i--) model.remove(selected[i]);
    }

    private DirectionChoice direction() {
        return directionBox.getSelectedItem() instanceof DirectionChoice value ? value : DirectionChoice.OUT;
    }
    private static DirectionChoice selectedDirection(JComboBox<DirectionChoice> box) {
        return box.getSelectedItem() instanceof DirectionChoice value
                ? value : DirectionChoice.OUT;
    }
    private GraphEvidenceCondition.ReviewDisposition reviewDisposition() {
        return reviewBox.getSelectedItem() instanceof GraphEvidenceCondition.ReviewDisposition value
                ? value : GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT;
    }
    private static GraphDiscoveryConfiguration.NodeUse use(JComboBox<GraphDiscoveryConfiguration.NodeUse> box) {
        return box.getSelectedItem() instanceof GraphDiscoveryConfiguration.NodeUse value
                ? value : GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY;
    }
    private static GeneratedClassModel selectedClass(JComboBox<GeneratedClassModel> box) {
        return box.getSelectedItem() instanceof GeneratedClassModel clazz ? clazz : null;
    }
    private static void selectClass(JComboBox<GeneratedClassModel> box, String name) {
        if (name == null) return;
        for (int i = 0; i < box.getItemCount(); i++) {
            GeneratedClassModel clazz = box.getItemAt(i);
            if (clazz.className().equalsIgnoreCase(name)) { box.setSelectedIndex(i); return; }
        }
    }
    private void status(String text, boolean error) {
        status.setText(text == null || text.isBlank() ? " " : text);
        status.setForeground(error ? new Color(165, 40, 35) : UIManager.getColor("Label.foreground"));
    }
    private static String cleanPid(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private static String cleanQid(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private static <T> List<T> elements(DefaultListModel<T> model) {
        List<T> values = new ArrayList<>(model.size());
        for (int i = 0; i < model.size(); i++) values.add(model.get(i));
        return List.copyOf(values);
    }
    private static <T> void replace(DefaultListModel<T> model, List<T> values) {
        model.clear(); if (values != null) values.forEach(model::addElement);
    }

    ProcessWorkflowResults<GraphDiscoveryResultStore.Artifact> graphResults(
            ConfiguredGraphDiscoveryQuery.Result result, String graphName) {
        return graphResults(result, model.name(), graphName);
    }

    ProcessWorkflowResults<GraphDiscoveryResultStore.Artifact> graphResults(
            ConfiguredGraphDiscoveryQuery.Result result,
            String projectName, String graphName) {
        GraphDiscoveryResultStore.Artifact artifact =
                GraphDiscoveryResultStore.artifact(projectName, graphName, result);
        preserveManualDecisions(lastGraphResult(), artifact);
        remember(graphName, artifact);
        var graph = result.graph();
        var last = graph.nodes().isEmpty() ? null : graph.nodes().getLast();
        int reached = last == null ? 0 : last.reached().size();
        int accepted = last == null ? 0 : last.accepted().size();
        int review = last == null ? 0 : last.review().size();
        int rejected = last == null ? 0 : last.rejected().size();
        int unavailable = last == null ? 0 : last.unavailable().size();
        int incomplete = last == null ? 0 : last.incomplete().size();
        String labels = result.labelledEntities() < graph.start().size() + reached
                ? "; labels loaded for the first " + result.labelledEntities() : "";
        String summary = reached + " reached: " + accepted + " accepted, " + review
                + " review, " + rejected + " rejected"
                + (unavailable + incomplete == 0 ? "" : "; " + unavailable
                        + " unavailable, " + incomplete + " incomplete") + labels
                + ". Apply result will install " + artifact.acceptedCandidates().size()
                + " " + artifact.outputClass() + " instances in " + projectName + ".";
        status(summary, false);
        return new ProcessWorkflowResults<>("Run graph — results", summary, "Apply result",
                List.of(artifactTab("All", artifact, null),
                        artifactTab("Accepted", artifact, "Accepted"),
                        artifactTab("Review", artifact, "Review"),
                        artifactTab("Rejected", artifact, "Rejected")),
                () -> artifact, "Close without applying result");
    }

    static String saveDescription(GraphDiscoveryResultStore.Artifact artifact) {
        return "Save graph annotations \"" + artifact.type() + "\" for \""
                + artifact.projectName() + "\" with " + artifact.instances().size()
                + " instance" + (artifact.instances().size() == 1 ? "" : "s")
                + " and their field model to "
                + GraphDiscoveryResultStore.destinationOf(artifact).getPath() + ".";
    }

    private void clearCompletedPopulation() {
        if (clazz != null) graphResults.remove(resultKey(clazz));
    }

    private static boolean sameSource(GraphClassSource left, GraphClassSource right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return java.util.Objects.equals(left.startNode(), right.startNode())
                && java.util.Objects.equals(left.nextNodes(), right.nextNodes());
    }

    private void remember(GraphDiscoveryResultStore.Artifact artifact) {
        if (artifact != null) remember(artifact.type(), artifact);
    }

    private void remember(String graphName, GraphDiscoveryResultStore.Artifact artifact) {
        GeneratedClassModel owner = model.findClass(graphName);
        if (owner == null) owner = clazz;
        if (owner != null && artifact != null) graphResults.put(resultKey(owner), artifact);
    }

    private static String resultKey(GeneratedClassModel graphClass) {
        String id = graphClass.declarationId();
        return id.isBlank() ? graphClass.className() : id;
    }

    static ProcessWorkflowResults.Tab<GraphDiscoveryResultStore.Artifact> artifactTab(
            String title, GraphDiscoveryResultStore.Artifact artifact, String decision) {
        List<ProcessWorkflowResults.Card<GraphDiscoveryResultStore.Artifact>> cards =
                artifact.instances().stream()
                        .filter(value -> decision == null
                                || decisionValue(value).contains(decision))
                        .map(value -> new ProcessWorkflowResults.Card<GraphDiscoveryResultStore.Artifact>(
                                value, () -> null, false,
                                () -> manualDecisionMark(value))).toList();
        List<ProcessWorkflowResults.SelectionAction> actions = List.of(
                selectionAction("Accept selection", "Accepted"),
                selectionAction("Reject selection", "Rejected"),
                selectionAction("Clear manual decision", null));
        return new ProcessWorkflowResults.Tab<>(title + " — " + cards.size() + " total", cards,
                artifact.model().representativeSample(artifact.type()), actions);
    }

    private static ProcessWorkflowResults.SelectionAction selectionAction(
            String label, String decision) {
        return new ProcessWorkflowResults.SelectionAction(label, values -> values.stream()
                .filter(WikidataDynamicObject.class::isInstance)
                .map(WikidataDynamicObject.class::cast)
                .forEach(value -> GraphDiscoveryResultStore.manualDecision(value, decision)));
    }

    private static JComponent manualDecisionMark(WikidataDynamicObject value) {
        Object decision = value.get(GraphDiscoveryResultStore.MANUAL_DECISION);
        if (decision == null) return null;
        JLabel mark = new JLabel("Accepted".equals(decision) ? "● accepted" : "● rejected");
        mark.setForeground("Accepted".equals(decision)
                ? new Color(35, 125, 55) : new Color(175, 45, 40));
        return mark;
    }

    private static void preserveManualDecisions(
            GraphDiscoveryResultStore.Artifact previous,
            GraphDiscoveryResultStore.Artifact replacement) {
        if (previous == null || replacement == null
                || !previous.type().equals(replacement.type())) return;
        Map<String, Object> decisions = new LinkedHashMap<>();
        for (WikidataDynamicObject value : previous.instances()) {
            Object decision = value.get(GraphDiscoveryResultStore.MANUAL_DECISION);
            if (decision != null) decisions.put(value.getIdentifier(), decision);
        }
        for (WikidataDynamicObject value : replacement.instances()) {
            Object decision = decisions.get(value.getIdentifier());
            if (decision != null) GraphDiscoveryResultStore.manualDecision(
                    value, String.valueOf(decision));
        }
    }

    private static List<String> decisionValue(WikidataDynamicObject value) {
        return GraphDiscoveryResultStore.originalDecisions(value);
    }

    static ProcessWorkflowResults.Tab<Void> resultTab(
            String title, List<EntityRef> entities,
            ConfiguredGraphDiscoveryQuery.Result result, String type) {
        List<EntityRef> values = entities == null ? List.of() : entities;
        List<ProcessWorkflowResults.Card<Void>> cards = values.stream()
                .map(entity -> {
            return new ProcessWorkflowResults.Card<Void>((Viewable) entityView(
                            entity, result, type),
                    () -> null, false);
        }).toList();
        return new ProcessWorkflowResults.Tab<>(
                title + " — " + values.size() + " total", cards);
    }

    /** Rejections stay inspectable as complete, virtualized member collections, grouped
     *  by the reason retained by the evaluator. The result view must not rediscover a
     *  reason from evidence edges: that would create a second classification path. */
    static ProcessWorkflowResults.Tab<Void> rejectedResultTab(
            datasource.graph.execution.GraphDiscoveryExecutor.NodeResult node,
            ConfiguredGraphDiscoveryQuery.Result result) {
        if (node == null) {
            return new ProcessWorkflowResults.Tab<>("Rejected — 0 total", List.of());
        }
        Map<RejectionReason, List<GraphEvidenceConditionResult>> grouped =
                new LinkedHashMap<>();
        node.classifications().stream()
                .filter(classification -> classification.decision()
                        == GraphEvidenceConditionResult.Decision.REJECTED)
                .forEach(classification -> grouped.computeIfAbsent(
                        RejectionReason.of(classification), ignored -> new ArrayList<>())
                        .add(classification));

        List<ProcessWorkflowResults.Card<Void>> cards = new ArrayList<>();
        int index = 0;
        for (Map.Entry<RejectionReason, List<GraphEvidenceConditionResult>> entry
                : grouped.entrySet()) {
            RejectionReason reason = entry.getKey();
            List<DynamicViewable> rejected = entry.getValue().stream()
                    .map(classification -> entityView(
                            classification.node(), result, "Rejected"))
                    .toList();
            DynamicViewable group = new DynamicViewable(
                    "rejection-reason-" + (++index), reason.reason());
            group.type("Rejection reason");
            if (!reason.condition().isBlank()) {
                group.put("Condition", reason.condition());
            }
            group.put("Count", rejected.size());
            group.put("Rejected nodes", rejected);
            cards.add(new ProcessWorkflowResults.Card<>((Viewable) group,
                    () -> null, false));
        }
        return new ProcessWorkflowResults.Tab<>(
                "Rejected — " + node.rejected().size() + " total", cards);
    }

    private static DynamicViewable entityView(EntityRef entity,
            ConfiguredGraphDiscoveryQuery.Result result, String type) {
        DynamicViewable card = new DynamicViewable(entity.id(), result.label(entity));
        card.type(type);
        card.put("QID", entity.id());
        return card;
    }

    private record RejectionReason(String condition, String reason) {
        private static RejectionReason of(GraphEvidenceConditionResult result) {
            String reason = result.reason().isBlank()
                    ? "No rejection reason was recorded" : result.reason();
            return new RejectionReason(result.conditionName(), reason);
        }
    }

    static DynamicViewable graphSummary(
            GeneratedProjectModel snapshot, GeneratedClassModel graphClass) {
        return graphSummary(snapshot, graphClass, List.of());
    }

    static DynamicViewable graphSummary(GeneratedProjectModel snapshot,
            GeneratedClassModel graphClass,
            java.util.Collection<? extends Viewable> instances) {
        GraphDiscoveryConfiguration graph = configurationOf(graphClass);
        DynamicViewable summary = new DynamicViewable("graph-plan", "Configured graph");
        summary.type("Graph discovery");
        summary.put("Adjacency facts",
                "Load saved answers from the persistent graph cache; download only missing answers");
        summary.put("Classification",
                "Repeat locally from the loaded graph facts");
        summary.put("Labels",
                "Fetch again; labels are not stored in the graph cache");
        summary.put("Results",
                "Rebuild and save every start and reached entity for TransformApp");
        summary.put("Results file",
                GraphDiscoveryResultStore.destination(
                        snapshot.name(), graph.name()).getPath());
        summary.put("Annotation set", graph.name());
        summary.put("Start class", startClassName(snapshot, graph.startNode()));
        summary.put("Start input", graph.startNode().populationSelection().isBlank()
                ? "All loaded " + graph.startNode().qidSourceClass() + " instances"
                : "Saved population " + graph.startNode().populationSelection());
        summary.put("Start QIDs", startQidCount(snapshot, graph.startNode(), instances));
        if (!graph.nextNodes().isEmpty()) {
            GraphDiscoveryConfiguration.NextNode next = graph.nextNodes().getFirst();
            summary.put("Edge", next.property().relationId() + " "
                    + directionLabel(next.directionFromPrevious()));
            summary.put("Reached entities", next.use()
                    == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION
                    ? "Members of " + next.populationClass() : "Intermediate only");
            GraphEvidenceCondition evidence = next.evidenceCondition();
            summary.put("Evidence relations",
                    evidence == null ? 0 : evidence.evidencePaths().size());
            summary.put("Evidence tests", evidence == null ? 0 : evidence.tests().size());
        }
        return summary;
    }

    private static JComponent graphPlanView(
            GeneratedProjectModel snapshot, GeneratedClassModel graphClass) {
        return graphPlanView(snapshot, graphClass, List.of());
    }

    private static JComponent graphPlanView(GeneratedProjectModel snapshot,
            GeneratedClassModel graphClass,
            java.util.Collection<? extends Viewable> instances) {
        GraphDiscoveryPlanDiagram diagram = new GraphDiscoveryPlanDiagram(
                graphPlanModel(snapshot, graphClass, instances), reviewPolicy(graphClass));
        return new JScrollPane(diagram);
    }

    /** One graph-model projection of the configuration used by both tests and the plan view. */
    static GraphViewModel graphPlanModel(
            GeneratedProjectModel snapshot, GeneratedClassModel graphClass) {
        return graphPlanModel(snapshot, graphClass, List.of());
    }

    static GraphViewModel graphPlanModel(GeneratedProjectModel snapshot,
            GeneratedClassModel graphClass,
            java.util.Collection<? extends Viewable> instances) {
        GraphDiscoveryConfiguration graph = configurationOf(graphClass);
        if (graph == null) return new GraphViewModel(List.of(), List.of());
        List<GraphViewModel.Node> nodes = new ArrayList<>();
        List<GraphViewModel.Edge> edges = new ArrayList<>();
        nodes.add(new GraphViewModel.Node("start", startClassName(snapshot, graph.startNode()), null,
                0, GraphViewModel.State.EXPANDED,
                java.util.Map.of(
                        "QIDs", Integer.toString(startQidCount(
                                snapshot, graph.startNode(), instances)),
                        "Population", graph.startNode().populationSelection().isBlank()
                                ? "All loaded class instances"
                                : graph.startNode().populationSelection(),
                        "Use", nodeUse(graph.startNode().use(),
                                startClassName(snapshot, graph.startNode()))),
                graph.startNode()));
        String previous = "start";
        int index = 0;
        for (GraphDiscoveryConfiguration.NextNode next : graph.nextNodes()) {
            int nextLevel = index * 3 + 1;
            String nextId = "next-" + index;
            String nextLabel = next.use() == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION
                    ? next.populationClass() : "Intermediate node";
            nodes.add(new GraphViewModel.Node(nextId, nextLabel, null, nextLevel,
                    GraphViewModel.State.DEFAULT,
                    java.util.Map.of("Use", nodeUse(next.use(), next.populationClass())), next));
            edges.add(new GraphViewModel.Edge("traversal-" + index, previous, nextId,
                    edgeLabel(next.property(), next.directionFromPrevious()), true));

            GraphEvidenceCondition evidence = next.evidenceCondition();
            if (evidence != null) {
                for (int pathIndex = 0; pathIndex < evidence.evidencePaths().size(); pathIndex++) {
                    GraphPath path = evidence.evidencePaths().get(pathIndex);
                    String evidenceId = "evidence-" + index + "-" + pathIndex;
                    nodes.add(new GraphViewModel.Node(evidenceId, "Evidence entity", null,
                            nextLevel + 1, GraphViewModel.State.DEFAULT,
                            java.util.Map.of("Condition", evidence.name()), path));
                    edges.add(new GraphViewModel.Edge("evidence-edge-" + index + "-" + pathIndex,
                            nextId, evidenceId, edgeLabel(path.relation(), path.direction()), true));
                    for (int testIndex = 0; testIndex < evidence.tests().size(); testIndex++) {
                        GraphNodeCondition test = evidence.tests().get(testIndex);
                        String testId = "test-" + index + "-" + testIndex;
                        if (nodes.stream().noneMatch(node -> node.id().equals(testId))) {
                            nodes.add(testNode(testId, nextLevel + 2, test));
                        }
                        edges.add(new GraphViewModel.Edge("test-edge-" + index + "-"
                                + pathIndex + "-" + testIndex, evidenceId, testId,
                                conditionEdgeLabel(test), true));
                    }
                }
            }
            previous = nextId;
            index++;
        }
        return new GraphViewModel(nodes, edges);
    }

    private static GraphViewModel.Node testNode(
            String id, int level, GraphNodeCondition condition) {
        String label;
        String result;
        if (condition instanceof GraphRelationExists) {
            label = "Has a value";
            result = "Accept when present";
        } else if (condition instanceof GraphRelationAbsent) {
            label = "Has no value";
            result = "Accept when absent";
        } else if (condition instanceof GraphRelationReaches reaches) {
            label = reaches.entity().id();
            result = "Accept when reached";
        } else {
            label = "Evidence test";
            result = "Configured condition";
        }
        return new GraphViewModel.Node(id, label, null, level,
                GraphViewModel.State.FRONTIER, java.util.Map.of("Result", result), condition);
    }

    private static String conditionEdgeLabel(GraphNodeCondition condition) {
        if (condition instanceof GraphRelationExists exists) {
            return edgeLabel(exists.relation(), exists.direction());
        }
        if (condition instanceof GraphRelationAbsent absent) {
            return edgeLabel(absent.relation(), absent.direction());
        }
        if (condition instanceof GraphRelationReaches reaches) {
            return edgeLabel(reaches.relation(), reaches.direction());
        }
        return "test";
    }

    private static String edgeLabel(
            GraphRelation relation, GraphTraversalDirection direction) {
        return relation.relationId() + " " + directionLabel(direction);
    }

    private static String nodeUse(
            GraphDiscoveryConfiguration.NodeUse use, String populationClass) {
        return use == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION
                ? "Add to " + populationClass : "Intermediate only";
    }

    private static String reviewPolicy(GeneratedClassModel graphClass) {
        GraphDiscoveryConfiguration graph = configurationOf(graphClass);
        if (graph == null) return "";
        return graph.nextNodes().stream().map(GraphDiscoveryConfiguration.NextNode::evidenceCondition)
                .filter(java.util.Objects::nonNull)
                .map(condition -> condition.reviewDisposition()
                        == GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT
                        ? "Undecidable nodes continue and are reported in Review."
                        : "Undecidable nodes stop and are reported in Review.")
                .distinct().collect(java.util.stream.Collectors.joining(" "));
    }

    private static List<String> graphDetails(
            GeneratedProjectModel snapshot, GeneratedClassModel graphClass) {
        GraphDiscoveryConfiguration graph = configurationOf(graphClass);
        if (graph == null || graph.nextNodes().isEmpty()) return List.of();
        GraphDiscoveryConfiguration.NextNode next = graph.nextNodes().getFirst();
        return List.of(startInputLabel(snapshot, graph.startNode()) + " → "
                + next.property().relationId() + " "
                + directionLabel(next.directionFromPrevious()) + " → "
                + (next.populationClass().isBlank() ? "intermediate node"
                : next.populationClass()));
    }

    private static int startQidCount(GeneratedProjectModel model,
            GraphDiscoveryConfiguration.StartNode start,
            java.util.Collection<? extends Viewable> instances) {
        Selection selection = model.findSelection(start.populationSelection());
        if (selection instanceof PopulationSelection population) {
            return population.instanceQids().size();
        }
        return loadedQids(instances, start.qidSourceClass()).size();
    }

    private static String startClassName(GeneratedProjectModel model,
            GraphDiscoveryConfiguration.StartNode start) {
        Selection selection = model.findSelection(start.populationSelection());
        return selection instanceof PopulationSelection population
                ? population.className() : start.qidSourceClass();
    }

    private static String startInputLabel(GeneratedProjectModel model,
            GraphDiscoveryConfiguration.StartNode start) {
        return start.populationSelection().isBlank()
                ? "All loaded " + start.qidSourceClass() + " instances"
                : "Population " + start.populationSelection() + " ("
                        + startClassName(model, start) + ")";
    }

    private void updateRunEnabled() {
        GraphDiscoveryConfiguration graph = configurationOf(clazz);
        // A saved start node is not yet a traversal: with no edge there is nothing to
        // follow, so the graph is removable and readable but not runnable. Nor is an
        // unnamed one: the name is where the annotation set is written, so running
        // without it would produce a result with nowhere to go.
        run.setEnabled(runner != null && !runner.isRunning()
                && graph != null && !graph.nextNodes().isEmpty()
                && !graph.name().isBlank());
    }
    private static String message(Throwable error) {
        return error == null || error.getMessage() == null
                ? "Unknown error" : error.getMessage();
    }
}
