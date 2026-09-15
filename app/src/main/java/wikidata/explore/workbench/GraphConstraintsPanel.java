package wikidata.explore.workbench;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.*;
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
     * population and no snapshot move until #184 consumes the result. "Applied" is
     * the word a reader takes for "in effect", so every line that reports a saved
     * graph carries this — the one after Apply most of all, because that is the line
     * shown at the moment the modeller acts. Delete it when generation consumes the
     * graph.
     */
    private static final String RUN_ONLY =
            " Generation does not use it yet (#184); Run graph is the only thing that"
                    + " executes it, and it changes no class population.";

    private final GeneratedProjectModel model;
    /** Whether the controls hold THIS model's graph, as opposed to construction
     *  defaults. Read by {@link #applyPendingEdits()}; see there for why it matters. */
    private boolean populated;
    private final JComboBox<GeneratedClassModel> startClassBox = new JComboBox<>();
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
    /** The model holds ONE discovery graph, so removing it needs no selection — the
     *  button names the thing it removes. Enabled only while there is one to remove,
     *  which is also what keeps an unconfigured panel from offering a destructive act. */
    private final JButton removeGraph = new JButton("Remove discovery graph");
    private SwingProcessRunner runner;
    private boolean runWired;
    private Consumer<Void> afterChange = ignored -> {};
    private BiConsumer<String, String> errorDialog;
    private boolean loading;

    GraphConstraintsPanel(GeneratedProjectModel model) {
        super(new BorderLayout(8, 8));
        this.model = java.util.Objects.requireNonNull(model, "model");
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

    private void runGraph() {
        if (runner == null || runner.isRunning()) return;
        try {
            GeneratedProjectModel snapshot = model.copy();
            ConfiguredGraphDiscoveryQuery query =
                    new ConfiguredGraphDiscoveryQuery(snapshot);
            ProcessWorkflowPipeline pipeline = new ProcessWorkflowPipeline(List.of(
                    new ProcessWorkflowPipeline.Phase(
                            "discover-graph", "Discover and classify graph nodes",
                            "Follow the configured edge, acquire evidence and classify "
                                    + "each reached entity.", graphDetails(snapshot))));
            ProcessWorkflowAction<ConfiguredGraphDiscoveryQuery.Result,
                    GraphDiscoveryResultStore.Artifact> action =
                    graphWorkflow(query, pipeline, snapshot, graphSummary(snapshot));
            SwingProcessWorkflow.start(this, runner, action);
        } catch (Exception error) {
            showGraphFailure(error);
        }
    }

    private ProcessWorkflowAction<ConfiguredGraphDiscoveryQuery.Result,
            GraphDiscoveryResultStore.Artifact> graphWorkflow(
            ConfiguredGraphDiscoveryQuery query, ProcessWorkflowPipeline pipeline,
            GeneratedProjectModel snapshot, DynamicViewable summary) {
        return new ProcessWorkflowAction<>() {
            @Override public String id() { return "discover-graph"; }
            @Override public ProcessWorkflowPipeline pipeline() { return pipeline; }
            @Override public java.util.function.Function<Object, String> valueLinker() {
                return WikidataLinks.valueLinker();
            }
            @Override public ProcessWorkflowPlan plan() {
                return new ProcessWorkflowPlan(
                        "Run graph", "Inspect the configured traversal and evidence tests, "
                                + "then explicitly start discovery.",
                        List.of(ProcessWorkflowPlan.Tab.component(
                                        "Graph", () -> graphPlanView(snapshot), true),
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
                return graphResults(outcome.result(), snapshot.name());
            }
            @Override public void apply(List<GraphDiscoveryResultStore.Artifact> decisions)
                    throws Exception {
                GraphDiscoveryResultStore.save(snapshot.name(), decisions.getFirst());
            }
        };
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
            GraphDiscoveryConfiguration saved = model.graphDiscoveryConfiguration();
            if (saved != null) {
                selectClass(startClassBox, saved.startNode().qidSourceClass());
                startUseBox.setSelectedItem(saved.startNode().use());
                if (!saved.nextNodes().isEmpty()) load(saved.nextNodes().getFirst());
            }
            refreshForStartClass();
            refreshTargetState();
            refreshArrow();
            status(saved == null ? "No discovery graph configured."
                    : saved.nextNodes().isEmpty()
                    ? "Start node saved: " + saved.startNode().qidSourceClass()
                            + ". Add the property connecting the two nodes to complete"
                            + " the graph."
                    : "Configured." + RUN_ONLY, false);
            updateRunEnabled();
            populated = true;
        } finally { loading = false; }
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
        if (model.graphDiscoveryConfiguration() == null
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
        GeneratedClassModel start = selectedClass(startClassBox);
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
                model.graphDiscoveryConfiguration(new GraphDiscoveryConfiguration(
                        new GraphDiscoveryConfiguration.StartNode(
                                start.className(), use(startUseBox)),
                        List.of()));
                status("Start node saved: " + start.className()
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
            GraphDiscoveryConfiguration.NodeUse targetUse = use(targetUseBox);
            GeneratedClassModel targetClass = selectedClass(targetClassBox);
            var target = new GraphDiscoveryConfiguration.NextNode(
                    new GraphRelation(PROVIDER, pid), direction().direction, targetUse,
                    targetUse == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION
                            && targetClass != null ? targetClass.className() : "",
                    evidence);
            model.graphDiscoveryConfiguration(new GraphDiscoveryConfiguration(
                    new GraphDiscoveryConfiguration.StartNode(start.className(), use(startUseBox)),
                    List.of(target)));
            status("Applied discovery graph." + RUN_ONLY, false);
            updateRunEnabled();
            afterChange.accept(null);
        } catch (IllegalArgumentException ex) { status(ex.getMessage(), true); }
    }

    private void buildUi() {
        startClassBox.setName("graph.startClass");
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
        status.setName("graph.status");
        removeGraph.setName("graph.remove");

        JPanel chain = new JPanel();
        chain.setLayout(new BoxLayout(chain, BoxLayout.X_AXIS));
        chain.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        chain.add(startNodePanel());
        chain.add(Box.createHorizontalStrut(8));
        chain.add(edgePanel());
        chain.add(Box.createHorizontalStrut(8));
        chain.add(nextNodePanel());
        JComponent graph = objectview.utils.swing.ScrollPaneUtils.horizontalOnly(chain);
        add(graph, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clear = new JButton("Clear draft");
        JButton apply = new JButton("Apply graph");
        actions.add(run); actions.add(removeGraph); actions.add(clear); actions.add(apply);
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(actions, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        startClassBox.addActionListener(e -> { if (!loading) refreshForStartClass(); });
        edgePidField.getDocument().addDocumentListener(SimpleDocumentListener.of(this::refreshArrow));
        directionBox.addActionListener(e -> refreshArrow());
        targetUseBox.addActionListener(e -> refreshTargetState());
        testKindBox.addActionListener(e -> refreshTestQidState());
        apply.addActionListener(e -> applyEdits());
        removeGraph.addActionListener(e -> removeGraph());
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
        addLine(panel, "Start with QIDs configured on class:", startClassBox);
        addLine(panel, "Configured QIDs:", startQids);
        addLine(panel, "Use these entities as:", startUseBox);
        panel.add(new JLabel("The graph has no separate QID list."));
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
        addLine(panel, "Use reached entities as:", targetUseBox);
        addLine(panel, "Members of class:", targetClassBox);
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
        panel.add(removeTest);
        addLine(panel, "When evidence cannot decide:", reviewBox);
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
        startClassBox.removeAllItems(); targetClassBox.removeAllItems();
        for (GeneratedClassModel clazz : model.classes()) {
            if (clazz == null || clazz.isImported()) continue;
            startClassBox.addItem(clazz); targetClassBox.addItem(clazz);
        }
    }

    private void refreshForStartClass() {
        boolean previousLoading = loading;
        loading = true;
        try {
        GeneratedClassModel start = selectedClass(startClassBox);
        int count = start == null ? 0 : start.seedQids().size();
        startQids.setText(count + (count == 1 ? " QID" : " QIDs"));
        } finally {
            loading = previousLoading;
        }
    }

    private void refreshTargetState() {
        targetClassBox.setEnabled(use(targetUseBox)
                == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION);
    }

    private void refreshArrow() {
        String pid = cleanPid(edgePidField.getText());
        arrowLabel.setText("── " + (pid.isBlank() ? "property" : pid) + " " + direction() + " ──▶");
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

    private void load(GraphDiscoveryConfiguration.NextNode node) {
        edgePidField.setText(node.property().relationId());
        directionBox.setSelectedItem(node.directionFromPrevious() == GraphTraversalDirection.INCOMING
                ? DirectionChoice.IN : DirectionChoice.OUT);
        targetUseBox.setSelectedItem(node.use());
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

    private static ListCellRenderer<Object> conditionRenderer() {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof GraphRelationExists c) setText(c.relation().relationId() + " has a value");
                else if (value instanceof GraphRelationAbsent c) setText(c.relation().relationId() + " has no value");
                else if (value instanceof GraphRelationReaches c) setText(c.relation().relationId() + " reaches " + c.entity().id());
                return this;
            }
        };
    }

    private static ListCellRenderer<Object> pathRenderer() {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof GraphPath path) {
                    setText(path.relation().relationId() + " "
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
                        ? "Exclude from the next step and report in Review"
                        : "Include in the next step and report in Review");
                return this;
            }
        });
        return box;
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
            ConfiguredGraphDiscoveryQuery.Result result, String projectName) {
        GraphDiscoveryResultStore.Artifact artifact =
                GraphDiscoveryResultStore.artifact(result);
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
                + ". No class population was changed. "
                + saveDescription(projectName, artifact);
        status(summary, false);
        String saveDescription = saveDescription(projectName, artifact);
        return new ProcessWorkflowResults<>("Run graph — results", summary, "Save result",
                List.of(artifactTab("Start", artifact, "Start"),
                        artifactTab("Accepted", artifact, "Accepted"),
                        artifactTab("Review", artifact, "Review"),
                        artifactTab("Rejected", artifact, "Rejected")),
                () -> artifact, "Close without saving result", saveDescription);
    }

    static String saveDescription(String projectName,
                                  GraphDiscoveryResultStore.Artifact artifact) {
        return new quiz.transform.app.DomainSaver().describeSave(
                GraphDiscoveryResultStore.domainName(projectName),
                artifact.instances(), artifact.model());
    }

    static ProcessWorkflowResults.Tab<GraphDiscoveryResultStore.Artifact> artifactTab(
            String title, GraphDiscoveryResultStore.Artifact artifact, String decision) {
        List<ProcessWorkflowResults.Card<GraphDiscoveryResultStore.Artifact>> cards =
                artifact.instances().stream()
                        .filter(value -> decisionValue(value).contains(decision))
                        .map(value -> new ProcessWorkflowResults.Card<GraphDiscoveryResultStore.Artifact>(
                                value, () -> null, false)).toList();
        return new ProcessWorkflowResults.Tab<>(title + " — " + cards.size() + " total", cards,
                artifact.model().representativeSample(GraphDiscoveryResultStore.TYPE));
    }

    private static List<String> decisionValue(WikidataDynamicObject value) {
        Object decision = value.get("Decision");
        if (decision instanceof List<?> values) return values.stream().map(String::valueOf).toList();
        return decision == null ? List.of() : List.of(String.valueOf(decision));
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

    static DynamicViewable graphSummary(GeneratedProjectModel snapshot) {
        GraphDiscoveryConfiguration graph = snapshot.graphDiscoveryConfiguration();
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
                GraphDiscoveryResultStore.destination(snapshot.name()).getPath());
        summary.put("Start class", graph.startNode().qidSourceClass());
        GeneratedClassModel start = snapshot.findClass(graph.startNode().qidSourceClass());
        summary.put("Start QIDs", start == null ? 0 : start.seedQids().size());
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

    private static JComponent graphPlanView(GeneratedProjectModel snapshot) {
        GraphDiscoveryPlanDiagram diagram = new GraphDiscoveryPlanDiagram(
                graphPlanModel(snapshot), reviewPolicy(snapshot));
        return new JScrollPane(diagram);
    }

    /** One graph-model projection of the configuration used by both tests and the plan view. */
    static GraphViewModel graphPlanModel(GeneratedProjectModel snapshot) {
        GraphDiscoveryConfiguration graph = snapshot.graphDiscoveryConfiguration();
        if (graph == null) return new GraphViewModel(List.of(), List.of());
        List<GraphViewModel.Node> nodes = new ArrayList<>();
        List<GraphViewModel.Edge> edges = new ArrayList<>();
        GeneratedClassModel startClass = snapshot.findClass(graph.startNode().qidSourceClass());
        nodes.add(new GraphViewModel.Node("start", graph.startNode().qidSourceClass(), null,
                0, GraphViewModel.State.EXPANDED,
                java.util.Map.of(
                        "QIDs", Integer.toString(startClass == null
                                ? 0 : startClass.seedQids().size()),
                        "Use", nodeUse(graph.startNode().use(), graph.startNode().qidSourceClass())),
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

    private static String reviewPolicy(GeneratedProjectModel snapshot) {
        GraphDiscoveryConfiguration graph = snapshot.graphDiscoveryConfiguration();
        if (graph == null) return "";
        return graph.nextNodes().stream().map(GraphDiscoveryConfiguration.NextNode::evidenceCondition)
                .filter(java.util.Objects::nonNull)
                .map(condition -> condition.reviewDisposition()
                        == GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT
                        ? "Undecidable nodes continue and are reported in Review."
                        : "Undecidable nodes stop and are reported in Review.")
                .distinct().collect(java.util.stream.Collectors.joining(" "));
    }

    private static List<String> graphDetails(GeneratedProjectModel snapshot) {
        GraphDiscoveryConfiguration graph = snapshot.graphDiscoveryConfiguration();
        if (graph == null || graph.nextNodes().isEmpty()) return List.of();
        GraphDiscoveryConfiguration.NextNode next = graph.nextNodes().getFirst();
        return List.of(graph.startNode().qidSourceClass() + " → "
                + next.property().relationId() + " "
                + directionLabel(next.directionFromPrevious()) + " → "
                + (next.populationClass().isBlank() ? "intermediate node"
                : next.populationClass()));
    }

    private void updateRunEnabled() {
        GraphDiscoveryConfiguration graph = model.graphDiscoveryConfiguration();
        // A saved start node is not yet a traversal: with no edge there is nothing to
        // follow, so the graph is removable and readable but not runnable.
        run.setEnabled(runner != null && !runner.isRunning()
                && graph != null && !graph.nextNodes().isEmpty());
        removeGraph.setEnabled(graph != null);
    }

    /** Removes the one graph the model holds. Explicit, named and separate from Apply,
     *  so deleting authored configuration can never be something a save infers. */
    private void removeGraph() {
        if (model.graphDiscoveryConfiguration() == null) return;
        model.graphDiscoveryConfiguration(null);
        status("Discovery graph removed. Save the domain to keep that.", false);
        updateRunEnabled();
        afterChange.accept(null);
    }
    private static String message(Throwable error) {
        return error == null || error.getMessage() == null
                ? "Unknown error" : error.getMessage();
    }
}
