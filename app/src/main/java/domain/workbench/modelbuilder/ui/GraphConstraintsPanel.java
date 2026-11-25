package domain.workbench.modelbuilder.ui;

import domain.build.GraphResults;

import domain.build.GraphDiscoveryResultStore;

import domain.datasource.EntityRef;
import domain.datasource.graph.GraphDiscoveryConfiguration;
import domain.datasource.graph.GraphRelation;
import domain.datasource.graph.GraphTraversalDirection;
import domain.datasource.graph.constraint.*;
import domain.datasource.graph.execution.GraphDiscoveryExecutor;
import objectview.Viewable;
import process.swing.SwingProcessRunner;
import process.swing.workflow.ProcessWorkflowResults;
import process.swing.workflow.SwingProcessWorkflow;
import domain.transform.DynamicViewable;
import wikidata.WikidataIds;
import domain.runtime.WikidataDynamicObject;
import domain.config.*;
import wikidata.explore.query.logical.ConfiguredGraphDiscoveryQuery;
import domain.workbench.ui.SimpleDocumentListener;
import domain.workbench.ui.ConfigurationTreePanel;

import static domain.workbench.modelbuilder.ui.GraphRunWorkflow.configurationOf;

import static wikidata.explore.query.logical.ConfiguredGraphDiscoveryQuery.loadedQids;

import static domain.workbench.modelbuilder.ui.GraphRunWorkflow.directionLabel;

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
        REACHES_UNDER("reaches QID or anything under it"),
        NODE_IS_UNDER("node is QID or anything under it"),
        HAS_NO_VALUE("has no value");
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
    private final ConfigurationTreePanel configuration = new ConfigurationTreePanel(
            "modelbuilder.graph.configuration", "Configuration");
    private final Map<String, JComponent> configurationDetails = new LinkedHashMap<>();
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
    private final JTextField alternativePidField = new JTextField(8);
    private final JComboBox<DirectionChoice> alternativeDirectionBox = new JComboBox<>(DirectionChoice.values());
    // The property and direction compose ONE alternative; the list holds the ones
    // composed so far. A single field could only ever say the first of them, and
    // applying then rewrote the rest away without saying so.
    private final DefaultListModel<GraphDiscoveryConfiguration.Edge> alternativeModel =
            new DefaultListModel<>();
    private final JList<GraphDiscoveryConfiguration.Edge> alternativeList =
            new JList<>(alternativeModel);
    private final JLabel arrowLabel = new JLabel("── property out ──▶", SwingConstants.CENTER);
    private final JComboBox<GraphDiscoveryConfiguration.NodeUse> targetUseBox = useBox();
    private final DefaultListModel<GraphDiscoveryConfiguration.NextNode> nodesModel =
            new DefaultListModel<>();
    private final JList<GraphDiscoveryConfiguration.NextNode> nodesList =
            new JList<>(nodesModel);
    private final JComboBox<GeneratedClassModel> targetClassBox = new JComboBox<>();
    private final JComboBox<String> admissionPopulationBox = new JComboBox<>();
    private final JComboBox<GraphDiscoveryConfiguration.PopulationOperation> populationOperationBox =
            new JComboBox<>(GraphDiscoveryConfiguration.PopulationOperation.values());
    private final JCheckBox repeatUntilStable = new JCheckBox("Repeat until no new instances are found");
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
    // The form does not show the evidence condition's name, so editing a node must keep
    // the saved one rather than write a default the reader never saw.
    private String loadedEvidenceName = "";
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
    /** Applies a result to the project, or throws saying why it cannot — which keeps the
     *  results dialog open, so the reviewer can decide what it is waiting for. */
    @FunctionalInterface
    interface GraphResultApplier {
        void apply(GraphDiscoveryResultStore.Artifact result) throws Exception;
    }

    private GraphResultApplier graphResultApplier = ignored -> { };
    private Runnable afterGraphResultApplied = () -> {};
    /** The project's graph results. The editor records a run into them and reads the
     *  edited class's from them; it does not own them. */
    private final GraphResults results;

    GraphConstraintsPanel(GeneratedProjectModel model) {
        this(model, new GraphResults(model, dataset.DomainStorage.inDefaultLocation()));
    }

    GraphConstraintsPanel(GeneratedProjectModel model, GraphResults results) {
        super(new BorderLayout(8, 8));
        this.model = java.util.Objects.requireNonNull(model, "model");
        this.results = java.util.Objects.requireNonNull(results, "results");
        populationOperationBox.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setText(value == GraphDiscoveryConfiguration.PopulationOperation.ADD
                        ? "Add accepted instances to output class"
                        : "Keep only accepted instances in output class");
                return this;
            }
        });
        admissionPopulationBox.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setText(value == null || String.valueOf(value).isBlank()
                        ? "Any reached entity" : "Members of " + value);
                return this;
            }
        });
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

    void onNavigateConfiguration(
            Consumer<ConfigurationReferenceInventory.Site> value) {
        configuration.onNavigate(value);
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

    void onGraphResult(GraphResultApplier value) {
        graphResultApplier = value == null ? ignored -> { } : value;
    }

    void afterGraphResultApplied(Runnable value) {
        afterGraphResultApplied = value == null ? () -> {} : value;
    }

    GraphDiscoveryResultStore.Artifact lastGraphResult() {
        return results.of(clazz);
    }

    private void runGraph() {
        if (runner == null || runner.isRunning()) return;
        try {
            GeneratedClassModel graph = clazz;
            var previous = results.of(graph);
            var action = GraphRunWorkflow.action(model, graph, loadedInstances.get(),
                    results.destination(graph), new GraphRunWorkflow.ResultHandling(
                            "Apply result", "Close without applying result",
                            "Apply result updates the loaded " + graph.graphSource().outputClassName()
                                    + " population. Save model/domain writes the graph annotations.",
                            () -> previous, this::applyGraphResult, this::revealAppliedGraphResult));
            SwingProcessWorkflow.start(this, runner, action);
        } catch (Exception error) {
            showGraphFailure(error);
        }
    }

    void applyGraphResult(GraphDiscoveryResultStore.Artifact artifact) throws Exception {
        graphResultApplier.apply(artifact);
    }

    void revealAppliedGraphResult() {
        afterGraphResultApplied.run();
    }

    /** The graph a class declares, or null while it declares none. */
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
                replace(nodesModel, saved.nextNodes());
                if (!saved.nextNodes().isEmpty()) {
                    nodesList.setSelectedIndex(0);
                }
            }
            refreshTargetState();
            refreshArrow();
            status(saved == null ? "No discovery graph configured."
                    : saved.nextNodes().isEmpty()
                    ? "Start node saved: " + GraphRunWorkflow.startInputLabel(model, saved.startNode())
                            + ". Add the property connecting the two nodes to complete"
                            + " the graph."
                    : "Configured." + RUN_ONLY, false);
            updateRunEnabled();
            populated = true;
            refreshConfigurationTree();
        } finally { loading = false; }
    }

    /** An editor instance serves every graph class. Each selection starts from that
     * class's saved source, never from the controls left by the previously selected one. */
    private void clearDraftControls() {
        nodesModel.clear();
        clearNodeControls();
        if (!loading) refreshConfigurationTree();
    }

    private void clearNodeControls() {
        loadedEvidenceName = "";
        nodesList.clearSelection();
        edgePidField.setText("");
        directionBox.setSelectedItem(DirectionChoice.OUT);
        alternativePidField.setText("");
        alternativeDirectionBox.setSelectedItem(DirectionChoice.OUT);
        alternativeModel.clear();
        populationOperationBox.setSelectedItem(
                GraphDiscoveryConfiguration.PopulationOperation.NARROW);
        admissionPopulationBox.setSelectedItem("");
        repeatUntilStable.setSelected(false);
        evidenceModel.clear();
        testsModel.clear();
        evidencePidField.setText("");
        evidenceDirectionBox.setSelectedItem(DirectionChoice.OUT);
        testPidField.setText("");
        testDirectionBox.setSelectedItem(DirectionChoice.OUT);
        testKindBox.setSelectedItem(TestKind.HAS_VALUE);
        testQidField.setText("");
        testViaField.setText("");
        targetUseBox.setSelectedItem(GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION);
        reviewBox.setSelectedItem(
                GraphEvidenceCondition.ReviewDisposition.AWAIT_DECISION);
        if (targetClassBox.getItemCount() > 0) targetClassBox.setSelectedIndex(0);
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
        if (clazz == null || clazz.isImported()) return;
        if (clazz.graphSource() == null
                && nodesModel.isEmpty()
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
            if (pid.isEmpty() && nodesModel.isEmpty()
                    && evidenceModel.isEmpty() && testsModel.isEmpty()) {
                GraphClassSource replacement = new GraphClassSource(
                        new GraphDiscoveryConfiguration.StartNode(
                                start.population() ? "" : start.className(),
                                start.populationName(), use(startUseBox),
                                selectionId(start.populationName())),
                        List.of());
                boolean changed = !java.util.Objects.equals(clazz.graphSource(), replacement);
                clazz.graphSource(replacement);
                if (changed) clearCompletedPopulation();
                status("Start node saved: " + start
                        + ". Add the property connecting the two nodes to complete"
                        + " the graph.", false);
                updateRunEnabled();
                refreshConfigurationTree();
                afterChange.accept(null);
                return;
            }
            if (!pid.isEmpty()) upsertEditedNode(buildEditedNode());
            if (nodesModel.isEmpty()) {
                throw new IllegalArgumentException("Add at least one next node to the path");
            }
            List<GraphDiscoveryConfiguration.NextNode> nodes = elements(nodesModel);
            long outputs = nodes.stream().filter(node -> node.use()
                    == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION).count();
            if (outputs != 1 || nodes.getLast().use()
                    != GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION) {
                throw new IllegalArgumentException(
                        "The last node must be the graph's single output class; earlier nodes are Intermediate only");
            }
            GraphClassSource replacement = new GraphClassSource(
                    new GraphDiscoveryConfiguration.StartNode(
                            start.population() ? "" : start.className(),
                            start.populationName(), use(startUseBox),
                                selectionId(start.populationName())),
                    nodes);
            boolean changed = !java.util.Objects.equals(clazz.graphSource(), replacement);
            clazz.graphSource(replacement);
            if (changed) clearCompletedPopulation();
            status("Applied discovery graph." + RUN_ONLY, false);
            updateRunEnabled();
            refreshConfigurationTree();
            afterChange.accept(null);
        } catch (IllegalArgumentException ex) { status(ex.getMessage(), true); }
    }

    private void buildUi() {
        startInputBox.setName("graph.startInput");
        startUseBox.setName("graph.startUse");
        startQids.setName("graph.startQids");
        nodesList.setName("graph.nodes");
        edgePidField.setName("graph.edgeProperty");
        alternativePidField.setName("graph.alternativeProperty");
        alternativeDirectionBox.setName("graph.alternativeDirection");
        alternativeList.setName("graph.alternativeList");
        directionBox.setName("graph.edgeDirection");
        arrowLabel.setName("graph.edgeLabel");
        targetUseBox.setName("graph.targetUse");
        targetClassBox.setName("graph.targetClass");
        admissionPopulationBox.setName("graph.admissionPopulation");
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

        configurationDetails.put(GraphConfigurationProjection.DEFINITION,
                ConfigurationTreePanel.scrollDetail(header));
        configurationDetails.put(GraphConfigurationProjection.START,
                ConfigurationTreePanel.scrollDetail(startNodePanel()));
        configurationDetails.put(GraphConfigurationProjection.PATH,
                ConfigurationTreePanel.scrollDetail(pathPanel()));
        configurationDetails.put(GraphConfigurationProjection.EDGE,
                ConfigurationTreePanel.scrollDetail(edgePanel()));
        configurationDetails.put(GraphConfigurationProjection.NODE,
                ConfigurationTreePanel.scrollDetail(nodeSettingsPanel()));
        configurationDetails.put(GraphConfigurationProjection.EVIDENCE,
                ConfigurationTreePanel.scrollDetail(evidencePanel()));
        configurationDetails.put(GraphConfigurationProjection.IDENTITY,
                ConfigurationTreePanel.scrollDetail(identityEditor));
        configurationDetails.put(GraphConfigurationProjection.DISPLAY_NAME,
                ConfigurationTreePanel.scrollDetail(displayNameEditor));
        configuration.onSelection(this::configurationSelected);
        add(configuration, BorderLayout.CENTER);

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

        startInputBox.addActionListener(e -> {
            if (!loading) {
                refreshStartCount();
                refreshConfigurationTree();
            }
        });
        startUseBox.addActionListener(e -> {
            if (!loading) refreshConfigurationTree();
        });
        edgePidField.getDocument().addDocumentListener(SimpleDocumentListener.of(this::refreshArrow));
        directionBox.addActionListener(e -> refreshArrow());
        targetUseBox.addActionListener(e -> refreshTargetState());
        testKindBox.addActionListener(e -> refreshTestQidState());
        apply.addActionListener(e -> applyEdits());
        clear.addActionListener(e -> {
            clearDraftControls();
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
        addLine(panel, "Property:", WikidataIdLines.pid(edgePidField));
        addLine(panel, "Direction from previous node:", directionBox);
        panel.add(new JLabel("Equivalent alternative edges (optional; each is followed too)"));
        JPanel alternativeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        alternativeRow.add(new JLabel("Property:")); alternativeRow.add(WikidataIdLines.pid(alternativePidField));
        alternativeRow.add(alternativeDirectionBox);
        JButton addAlternative = new JButton("Add alternative edge");
        addAlternative.addActionListener(e -> addAlternativeEdge());
        alternativeRow.add(addAlternative); panel.add(alternativeRow);
        alternativeList.setVisibleRowCount(2);
        alternativeList.setCellRenderer(edgeRenderer());
        panel.add(new JScrollPane(alternativeList));
        JButton removeAlternative = new JButton("Remove selected alternative edge");
        removeAlternative.addActionListener(
                e -> removeSelected(alternativeList, alternativeModel));
        panel.add(removeAlternative);
        return panel;
    }

    private JPanel pathPanel() {
        JPanel panel = nodePanel("Path after the start node");
        nodesList.setVisibleRowCount(3);
        nodesList.setCellRenderer(nodeRenderer());
        nodesList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && nodesList.getSelectedValue() != null) {
                load(nodesList.getSelectedValue());
            }
        });
        panel.add(new JScrollPane(nodesList));
        JPanel nodeActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        JButton addNode = new JButton("Add node to path");
        addNode.addActionListener(event -> addEditedNode());
        JButton newNode = new JButton("New node");
        newNode.addActionListener(event -> clearNodeControls());
        JButton removeNode = new JButton("Remove selected node");
        removeNode.addActionListener(event -> removeSelectedNode());
        nodeActions.add(addNode); nodeActions.add(newNode); nodeActions.add(removeNode);
        panel.add(nodeActions);
        return panel;
    }

    private JPanel nodeSettingsPanel() {
        JPanel nodePanel = new JPanel();
        nodePanel.setLayout(new BoxLayout(nodePanel, BoxLayout.Y_AXIS));
        nodePanel.setBorder(BorderFactory.createTitledBorder("Selected path node"));
        addLine(nodePanel, "Use reached entities as:", targetUseBox);
        addLine(nodePanel, "Output class:", targetClassBox);
        addLine(nodePanel, "Admit and expand:", admissionPopulationBox);
        addLine(nodePanel, "Apply result:", populationOperationBox);
        nodePanel.add(repeatUntilStable);
        return nodePanel;
    }

    private JPanel evidencePanel() {
        JPanel evidencePanel = new JPanel();
        evidencePanel.setLayout(new BoxLayout(evidencePanel, BoxLayout.Y_AXIS));
        evidencePanel.setBorder(BorderFactory.createTitledBorder("Evidence for selected node"));
        evidencePanel.add(new JLabel(
                "Evidence relations from this node (one or more may reach evidence)"));
        JPanel evidenceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        evidenceRow.add(new JLabel("Property:")); evidenceRow.add(WikidataIdLines.pid(evidencePidField));
        evidenceRow.add(evidenceDirectionBox);
        JButton addEvidence = new JButton("Add evidence relation");
        evidenceRow.add(addEvidence); evidencePanel.add(evidenceRow);
        evidenceList.setVisibleRowCount(3);
        evidenceList.setCellRenderer(pathRenderer());
        evidencePanel.add(new JScrollPane(evidenceList));
        JButton removeEvidence = new JButton("Remove selected evidence relation");
        removeEvidence.addActionListener(e -> removeSelected(evidenceList, evidenceModel));
        JButton editEvidence = new JButton("Edit selected evidence relation");
        editEvidence.addActionListener(e -> editSelectedEvidence());
        evidencePanel.add(editEvidence);
        evidencePanel.add(removeEvidence);

        evidencePanel.add(new JLabel("Tests on reached evidence entities (one or more may match)"));
        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        testRow.add(new JLabel("Property:")); testRow.add(WikidataIdLines.pid(testPidField));
        testRow.add(testDirectionBox); testRow.add(testKindBox); testRow.add(WikidataIdLines.qid(testQidField));
        testRow.add(new JLabel("via")); testRow.add(testViaField);
        JButton addTest = new JButton("Add evidence test"); testRow.add(addTest);
        evidencePanel.add(testRow);
        testsList.setVisibleRowCount(3);
        testsList.setCellRenderer(conditionRenderer());
        evidencePanel.add(new JScrollPane(testsList));
        JButton removeTest = new JButton("Remove selected evidence test");
        removeTest.addActionListener(e -> removeSelected(testsList, testsModel));
        JButton editTest = new JButton("Edit selected evidence test");
        editTest.addActionListener(e -> editSelectedTest());
        evidencePanel.add(editTest);
        evidencePanel.add(removeTest);
        addLine(evidencePanel, "When evidence is incomplete:", reviewBox);
        addEvidence.addActionListener(e -> addEvidencePath());
        addTest.addActionListener(e -> addTest());
        return evidencePanel;
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

    private void refreshConfigurationTree() {
        if (clazz == null) {
            configuration.show(null, ignored -> null);
            return;
        }
        ConfigurationNode root = GraphConfigurationProjection.project(
                model, clazz, draftStart(), elements(nodesModel));
        configuration.show(root, node -> configurationDetails.getOrDefault(
                node.detailKey(), configurationDetails.get(
                        GraphConfigurationProjection.DEFINITION)));
    }

    private GraphDiscoveryConfiguration.StartNode draftStart() {
        StartInput start = selectedStart();
        if (start == null) return null;
        return new GraphDiscoveryConfiguration.StartNode(
                start.population() ? "" : start.className(), start.populationName(),
                use(startUseBox), selectionId(start.populationName()));
    }

    private void configurationSelected(ConfigurationNode node) {
        int selected = nodesList.getSelectedIndex();
        if (GraphConfigurationProjection.isAddNodeSlot(node.id())) {
            if (selected >= 0) clearNodeControls();
            return;
        }
        int index = GraphConfigurationProjection.pathNodeIndex(node.id());
        if (index >= 0 && index < nodesModel.size() && selected != index) {
            nodesList.setSelectedIndex(index);
        }
    }

    private void selectConfigurationNode(int index) {
        if (index < 0) return;
        selectConfigurationPath("path/node:" + index);
    }

    void selectConfigurationPath(String path) {
        configuration.nodes().stream()
                .filter(node -> node.id().key().equals(path))
                .findFirst().ifPresent(node -> configuration.select(node.id()));
    }

    ConfigurationTreePanel configurationTree() { return configuration; }

    boolean selectConfigurationDetail(String detailKey) {
        return configuration.selectDetail(detailKey);
    }

    private void refreshClasses() {
        targetClassBox.removeAllItems();
        for (GeneratedClassModel candidate : model.classes()) {
            // An imported class is a valid output: the project materializes its own
            // instances of it (History adds to the Position it imports). Leaving it out
            // made a saved imported output unshowable, and the flush then wrote over it.
            if (candidate == null) continue;
            // A graph produces entities, and the annotations about them are what a
            // graph class holds — so no graph class is another graph's output, this
            // one least of all.
            if (candidate.classKind() == ClassKind.GRAPH) continue;
            targetClassBox.addItem(candidate);
        }
        String keep = selectedAdmissionPopulation();
        admissionPopulationBox.removeAllItems();
        admissionPopulationBox.addItem("");
        for (Selection selection : model.selections()) {
            if (selection instanceof PopulationSelection) {
                admissionPopulationBox.addItem(selection.name());
            }
        }
        admissionPopulationBox.setSelectedItem(keep);
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

    private void refreshTargetState() {
        boolean output = targetUseBox.getSelectedItem()
                == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION;
        targetClassBox.setEnabled(output);
        populationOperationBox.setEnabled(output);
        refreshReviewChoices(output);
    }

    /**
     * Waiting for a decision is offered only where one can be made: the output node,
     * whose entities are the result. An intermediate node's entities are never shown for
     * a decision, so it chooses between including and excluding them.
     */
    private void refreshReviewChoices(boolean output) {
        Object current = reviewBox.getSelectedItem();
        GraphEvidenceCondition.ReviewDisposition[] choices = reviewDispositions(output);
        reviewBox.setModel(new DefaultComboBoxModel<>(choices));
        reviewBox.setSelectedItem(java.util.Arrays.asList(choices).contains(current)
                ? current : defaultDisposition(output));
    }

    static GraphEvidenceCondition.ReviewDisposition[] reviewDispositions(boolean output) {
        return output
                ? new GraphEvidenceCondition.ReviewDisposition[] {
                        GraphEvidenceCondition.ReviewDisposition.AWAIT_DECISION,
                        GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT,
                        GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT }
                : new GraphEvidenceCondition.ReviewDisposition[] {
                        GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT,
                        GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT };
    }

    private static GraphEvidenceCondition.ReviewDisposition defaultDisposition(boolean output) {
        return output ? GraphEvidenceCondition.ReviewDisposition.AWAIT_DECISION
                : GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT;
    }

    private void refreshArrow() {
        String pid = cleanPid(edgePidField.getText());
        arrowLabel.setText("── " + (pid.isBlank() ? "property" : labelledPid(pid))
                + " " + direction() + " ──▶");
    }

    private void refreshTestQidState() {
        // The target-QID tests name the entity they look for; only the generalising ones
        // also needs the relation that generalises it.
        Object kind = testKindBox.getSelectedItem();
        testQidField.setEnabled(
                kind == TestKind.REACHES_ENTITY || kind == TestKind.REACHES_UNDER
                        || kind == TestKind.NODE_IS_UNDER);
        testViaField.setEnabled(
                kind == TestKind.REACHES_UNDER || kind == TestKind.NODE_IS_UNDER);
        boolean followsProperty = kind != TestKind.NODE_IS_UNDER;
        testPidField.setEnabled(followsProperty);
        testDirectionBox.setEnabled(followsProperty);
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
        TestKind kind = (TestKind) testKindBox.getSelectedItem();
        if (kind == TestKind.NODE_IS_UNDER) {
            String qid = cleanQid(testQidField.getText());
            String via = cleanPid(testViaField.getText());
            if (!WikidataIds.isQid(qid)) {
                status("Enter the QID the evidence node must be or generalise to", true);
                return;
            }
            if (!WikidataIds.isPid(via)) {
                status("Enter the property that generalises it, for example P279", true);
                return;
            }
            GraphNodeCondition condition = GraphNodeIsUnder.of(
                    EntityRef.wikidata(qid), new GraphRelation(PROVIDER, via));
            if (!elements(testsModel).contains(condition)) testsModel.addElement(condition);
            testQidField.setText("");
            status("Draft changed; Apply graph to save it.", false);
            return;
        }
        String pid = cleanPid(testPidField.getText());
        if (!WikidataIds.isPid(pid)) {
            status("Enter the property tested on the evidence entity", true);
            return;
        }
        GraphRelation relation = new GraphRelation(PROVIDER, pid);
        GraphNodeCondition condition;
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
        } else if (condition instanceof GraphRelationReachesUnder value) {
            testKindBox.setSelectedItem(TestKind.REACHES_UNDER);
            relation = value.relation(); direction = value.direction();
            testQidField.setText(value.entity().id());
            testViaField.setText(value.via().relationId());
        } else if (condition instanceof GraphNodeIsUnder value) {
            testKindBox.setSelectedItem(TestKind.NODE_IS_UNDER);
            relation = value.via(); direction = GraphTraversalDirection.OUTGOING;
            testQidField.setText(value.entity().id());
            testViaField.setText(value.via().relationId());
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
        alternativePidField.setText("");
        alternativeDirectionBox.setSelectedItem(DirectionChoice.OUT);
        replace(alternativeModel, node.alternativeEdges());
        populationOperationBox.setSelectedItem(node.populationOperation());
        repeatUntilStable.setSelected(node.repeatUntilStable());
        admissionPopulationBox.setSelectedItem(node.admissionPopulationSelection());
        targetUseBox.setSelectedItem(node.use());
        if (!selectClass(targetClassBox, node.populationClass())
                && node.use() == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION) {
            // Show that the saved output cannot be shown; never let the box keep
            // whichever class it held, which the next flush would write over it.
            targetClassBox.setSelectedIndex(-1);
            status(missingOutputClass(node.populationClass()), true);
        }
        GraphEvidenceCondition evidence = node.evidenceCondition();
        loadedEvidenceName = evidence == null ? "" : evidence.name();
        replace(evidenceModel, evidence == null ? List.of() : evidence.evidencePaths());
        replace(testsModel, evidence == null ? List.of() : evidence.tests());
        reviewBox.setSelectedItem(evidence == null
                ? defaultDisposition(node.use()
                        == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION)
                : evidence.reviewDisposition());
    }

    private GraphDiscoveryConfiguration.NextNode buildEditedNode() {
        String pid = cleanPid(edgePidField.getText());
        if (!WikidataIds.isPid(pid)) {
            throw new IllegalArgumentException(
                    "Choose the property connecting this node to the previous node");
        }
        if (evidenceModel.isEmpty() != testsModel.isEmpty()) {
            throw new IllegalArgumentException(
                    "Add both an evidence relation and an evidence test, or leave both empty");
        }
        GraphEvidenceCondition evidence = evidenceModel.isEmpty() ? null
                : new GraphEvidenceCondition(
                        loadedEvidenceName.isBlank() ? "Node evidence" : loadedEvidenceName,
                        elements(evidenceModel),
                        elements(testsModel), reviewDisposition());
        GraphDiscoveryConfiguration.NodeUse use = use(targetUseBox);
        GeneratedClassModel targetClass = selectedClass(targetClassBox);
        if (use == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION
                && targetClass == null) {
            GraphDiscoveryConfiguration.NextNode saved = nodesList.getSelectedValue();
            throw new IllegalArgumentException(saved != null
                    && !saved.populationClass().isBlank()
                    ? missingOutputClass(saved.populationClass())
                    : "Choose the output class for this node");
        }
        return new GraphDiscoveryConfiguration.NextNode(
                new GraphRelation(PROVIDER, pid), direction().direction, use,
                use == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION
                        ? targetClass.className() : "",
                evidence, alternativeEdges(),
                (GraphDiscoveryConfiguration.PopulationOperation)
                        populationOperationBox.getSelectedItem(),
                repeatUntilStable.isSelected(), selectedAdmissionPopulation(),
                selectionId(selectedAdmissionPopulation()));
    }

    private void addEditedNode() {
        try {
            nodesModel.addElement(buildEditedNode());
            clearNodeControls();
            refreshConfigurationTree();
            status("Node added to the draft path; Apply graph to save it.", false);
        } catch (IllegalArgumentException error) {
            status(error.getMessage(), true);
        }
    }

    private void upsertEditedNode(GraphDiscoveryConfiguration.NextNode node) {
        int selected = nodesList.getSelectedIndex();
        if (selected >= 0) {
            nodesModel.set(selected, node);
            return;
        }
        // The draft now IS this node: select it, so the next Apply — or the flush every
        // Save performs — rewrites it instead of appending a copy.
        nodesModel.addElement(node);
        refreshConfigurationTree();
        selectConfigurationNode(nodesModel.size() - 1);
    }

    private void removeSelectedNode() {
        int selected = nodesList.getSelectedIndex();
        if (selected < 0) return;
        nodesModel.remove(selected);
        clearNodeControls();
        refreshConfigurationTree();
        status("Node removed from the draft path; Apply graph to save it.", false);
    }

    private ListCellRenderer<Object> nodeRenderer() {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof GraphDiscoveryConfiguration.NextNode node) {
                    String reached = node.use()
                            == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION
                            ? node.populationClass() + " output" : "intermediate";
                    setText((index + 1) + ". " + node.property().relationId() + " "
                            + directionLabel(node.directionFromPrevious()) + " → " + reached);
                }
                return this;
            }
        };
    }

    /** The chosen population's declaration id, kept beside its name so the reference
     *  survives a rename in the model that owns it. */
    private String selectionId(String name) {
        Selection selection = name == null || name.isBlank() ? null : model.findSelection(name);
        return selection == null ? "" : selection.declarationId();
    }

    private String selectedAdmissionPopulation() {
        Object selected = admissionPopulationBox.getSelectedItem();
        return selected == null ? "" : String.valueOf(selected).trim();
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
                if (value instanceof GraphNodeCondition condition) {
                    setText(GraphConfigurationProjection.evidenceTestCaption(
                            condition, GraphConstraintsPanel.this::labelledPid,
                            GraphConstraintsPanel.this::labelledQid));
                }
                return this;
            }
        };
    }

    private ListCellRenderer<Object> edgeRenderer() {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof GraphDiscoveryConfiguration.Edge edge) {
                    setText(labelledPid(edge.property().relationId()) + " "
                            + (edge.direction() == GraphTraversalDirection.INCOMING
                                    ? "incoming" : "outgoing"));
                }
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
        // The default leads; the other two decide for the reviewer.
        var box = new JComboBox<>(reviewDispositions(true));
        box.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setText(value instanceof GraphEvidenceCondition.ReviewDisposition disposition
                        ? GraphConfigurationProjection.reviewDispositionCaption(disposition) : "");
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
            String found = domain.provenance.SourceIdentities.wikidataQid(value);
            if (qid.equalsIgnoreCase(found)) {
                String label = value.getDisplayName();
                if (label != null && !label.isBlank() && !qid.equalsIgnoreCase(label)) {
                    return label + " (" + qid + ")";
                }
            }
        }
        return qid;
    }

    private static <T> void removeSelected(JList<T> list, DefaultListModel<T> model) {
        int[] selected = list.getSelectedIndices();
        for (int i = selected.length - 1; i >= 0; i--) model.remove(selected[i]);
    }

    private DirectionChoice direction() {
        return directionBox.getSelectedItem() instanceof DirectionChoice value ? value : DirectionChoice.OUT;
    }
    private List<GraphDiscoveryConfiguration.Edge> alternativeEdges() {
        return elements(alternativeModel);
    }

    /** Composes the typed property and direction into one more equivalent edge. */
    private void addAlternativeEdge() {
        String pid = cleanPid(alternativePidField.getText());
        if (!WikidataIds.isPid(pid)) {
            status("Enter a valid alternative property, such as P1366.", true);
            return;
        }
        GraphDiscoveryConfiguration.Edge edge = new GraphDiscoveryConfiguration.Edge(
                new GraphRelation(PROVIDER, pid),
                selectedDirection(alternativeDirectionBox).direction);
        if (elements(alternativeModel).contains(edge)) {
            status("That alternative edge is already listed.", true);
            return;
        }
        alternativeModel.addElement(edge);
        alternativePidField.setText("");
    }
    private static DirectionChoice selectedDirection(JComboBox<DirectionChoice> box) {
        return box.getSelectedItem() instanceof DirectionChoice value
                ? value : DirectionChoice.OUT;
    }
    private GraphEvidenceCondition.ReviewDisposition reviewDisposition() {
        return reviewBox.getSelectedItem() instanceof GraphEvidenceCondition.ReviewDisposition value
                ? value : GraphEvidenceCondition.ReviewDisposition.AWAIT_DECISION;
    }
    private static GraphDiscoveryConfiguration.NodeUse use(JComboBox<GraphDiscoveryConfiguration.NodeUse> box) {
        return box.getSelectedItem() instanceof GraphDiscoveryConfiguration.NodeUse value
                ? value : GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY;
    }
    private static GeneratedClassModel selectedClass(JComboBox<GeneratedClassModel> box) {
        return box.getSelectedItem() instanceof GeneratedClassModel clazz ? clazz : null;
    }
    private static String missingOutputClass(String name) {
        return "Saved output class '" + name
                + "' is not in this project. Choose an output class before applying.";
    }

    /** @return whether {@code name} is one of the box's classes and is now selected. */
    private static boolean selectClass(JComboBox<GeneratedClassModel> box, String name) {
        if (name == null) return false;
        for (int i = 0; i < box.getItemCount(); i++) {
            GeneratedClassModel clazz = box.getItemAt(i);
            if (clazz.className().equalsIgnoreCase(name)) { box.setSelectedIndex(i); return true; }
        }
        return false;
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
        results.record(owner(graphName), artifact);
        return GraphRunWorkflow.resultViews(result, artifact, "Apply result",
                "Close without applying result",
                "Apply result updates the loaded " + artifact.outputClass() + " population.");
    }

    static String saveDescription(GraphDiscoveryResultStore.Artifact artifact) {
        return "Save graph annotations \"" + artifact.type() + "\" for \""
                + artifact.projectName() + "\" with " + artifact.instances().size()
                + " instance" + (artifact.instances().size() == 1 ? "" : "s")
                + " and their field model to "
                + GraphDiscoveryResultStore.destinationOf(artifact).getPath() + ".";
    }

    /** A changed configuration is the one thing that makes a held result stale. */
    private void clearCompletedPopulation() {
        results.invalidate(clazz);
    }

    /** The graph class a run of that name belongs to; the edited one when none is. */
    private GeneratedClassModel owner(String graphName) {
        GeneratedClassModel owner = model.findClass(graphName);
        return owner == null ? clazz : owner;
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
            domain.datasource.graph.execution.GraphDiscoveryExecutor.NodeResult node,
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
            objectview.field.RecordTypes.declare("Rejection reason",
                    objectview.field.RecordTypes.text("Condition"),
                    objectview.field.RecordTypes.number("Count"),
                    objectview.field.RecordTypes.records("Rejected nodes", "Rejected"));
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
        objectview.field.RecordTypes.declare(type, objectview.field.RecordTypes.text("QID"));
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

    private void updateRunEnabled() {
        run.setEnabled(runner != null && !runner.isRunning()
                && GraphRunWorkflow.hasTraversal(clazz));
    }
    private static String message(Throwable error) {
        return error == null || error.getMessage() == null
                ? "Unknown error" : error.getMessage();
    }
}
