package wikidata.explore.workbench;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.*;
import objectview.Viewable;
import objectview.render.RenderingMode;
import objectview.view.SearchableView;
import quiz.transform.DynamicViewable;
import wikidata.WikidataIds;
import wikidata.explore.model.*;
import wikidata.explore.query.logical.ConfiguredGraphDiscoveryQuery;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.ui.WikidataLinks;
import workbench.SimpleDocumentListener;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** First bounded graph editor: configured-QID start, one property edge and one next node. */
final class GraphConstraintsPanel extends JPanel {
    private static final String PROVIDER = "wikidata";

    private enum TestKind {
        HAS_VALUE("has a value"), REACHES_ENTITY("reaches QID"), HAS_NO_VALUE("has no value");
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
    private final DefaultListModel<GraphNodeCondition> testsModel = new DefaultListModel<>();
    private final JList<GraphNodeCondition> testsList = new JList<>(testsModel);
    private final JComboBox<GraphEvidenceCondition.ReviewDisposition> reviewBox =
            reviewBox();
    private final JLabel status = new JLabel(" ");
    private final JButton run = new JButton("Run graph");
    private final JTabbedPane results = new JTabbedPane();
    private SwingQueryRunner runner;
    private boolean runWired;
    private Consumer<Void> afterChange = ignored -> {};
    private boolean loading;

    GraphConstraintsPanel(GeneratedProjectModel model) {
        super(new BorderLayout(8, 8));
        this.model = java.util.Objects.requireNonNull(model, "model");
        buildUi();
        refresh();
    }

    void afterChange(Consumer<Void> value) {
        afterChange = value == null ? ignored -> {} : value;
    }

    void setQueryRunner(SwingQueryRunner value) {
        runner = value;
        if (!runWired && runner != null) {
            runWired = true;
            runner.wireButton(run, this::showResult,
                    () -> new ConfiguredGraphDiscoveryQuery(model),
                    error -> status("Graph discovery failed: " + message(error), true));
            runner.onRunningChanged(ignored -> SwingUtilities.invokeLater(this::updateRunEnabled));
        }
        updateRunEnabled();
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
                    : "Configured." + RUN_ONLY, false);
            updateRunEnabled();
        } finally { loading = false; }
    }

    void applyEdits() {
        GeneratedClassModel start = selectedClass(startClassBox);
        if (start == null) return;
        try {
            String pid = cleanPid(edgePidField.getText());
            // An emptied draft is how a saved graph is removed, so that Apply stays the
            // one thing that changes the model. Clearing alone must not: every other
            // control here builds a draft, and one button writing straight through is
            // the difference between a panel with a commit point and one without.
            if (pid.isEmpty() && evidenceModel.isEmpty() && testsModel.isEmpty()) {
                boolean had = model.graphDiscoveryConfiguration() != null;
                model.graphDiscoveryConfiguration(null);
                status(had ? "Discovery graph removed." : "No discovery graph configured.",
                        false);
                updateRunEnabled();
                if (had) afterChange.accept(null);
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
        reviewBox.setName("graph.reviewDisposition");
        status.setName("graph.status");

        JPanel chain = new JPanel();
        chain.setLayout(new BoxLayout(chain, BoxLayout.X_AXIS));
        chain.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        chain.add(startNodePanel());
        chain.add(Box.createHorizontalStrut(8));
        chain.add(edgePanel());
        chain.add(Box.createHorizontalStrut(8));
        chain.add(nextNodePanel());
        JComponent graph = objectview.utils.swing.ScrollPaneUtils.horizontalOnly(chain);
        results.addTab("Start (0)", empty("Run the saved graph."));
        results.addTab("Accepted (0)", empty("Run the saved graph."));
        results.addTab("Review (0)", empty("Run the saved graph."));
        results.addTab("Rejected (0)", empty("Run the saved graph."));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, graph, results);
        split.setResizeWeight(0.58);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clear = new JButton("Clear draft");
        JButton apply = new JButton("Apply graph");
        actions.add(run); actions.add(clear); actions.add(apply);
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(actions, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        startClassBox.addActionListener(e -> { if (!loading) refreshForStartClass(); });
        edgePidField.getDocument().addDocumentListener(SimpleDocumentListener.of(this::refreshArrow));
        directionBox.addActionListener(e -> refreshArrow());
        targetUseBox.addActionListener(e -> refreshTargetState());
        testKindBox.addActionListener(e -> refreshTestQidState());
        apply.addActionListener(e -> applyEdits());
        clear.addActionListener(e -> {
            evidenceModel.clear();
            testsModel.clear();
            edgePidField.setText("");
            status(model.graphDiscoveryConfiguration() == null
                    ? "Draft cleared."
                    : "Draft cleared; Apply graph to remove the saved graph.", false);
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
        testQidField.setEnabled(testKindBox.getSelectedItem() == TestKind.REACHES_ENTITY);
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
        if (kind == TestKind.REACHES_ENTITY) {
            String qid = cleanQid(testQidField.getText());
            if (!WikidataIds.isQid(qid)) {
                status("Enter the QID the evidence property must reach", true);
                return;
            }
            condition = new GraphRelationReaches(relation, direction, EntityRef.wikidata(qid));
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

    private void showResult(ConfiguredGraphDiscoveryQuery.Result result) {
        if (result == null || result.graph() == null) return;
        var graph = result.graph();
        var last = graph.nodes().isEmpty() ? null : graph.nodes().getLast();
        setTab(0, "Start", graph.start(), result, "Start node");
        setTab(1, "Accepted", last == null ? List.of() : last.accepted(), result,
                last == null ? "Accepted" : last.traversal().targetNodeClass());
        setTab(2, "Review", last == null ? List.of() : last.review(), result, "Review");
        setTab(3, "Rejected", last == null ? List.of() : last.rejected(), result, "Rejected");
        int reached = last == null ? 0 : last.reached().size();
        int accepted = last == null ? 0 : last.accepted().size();
        int review = last == null ? 0 : last.review().size();
        int rejected = last == null ? 0 : last.rejected().size();
        int unavailable = last == null ? 0 : last.unavailable().size();
        int incomplete = last == null ? 0 : last.incomplete().size();
        String labels = result.labelledEntities() < graph.start().size() + reached
                ? "; labels loaded for the first " + result.labelledEntities() : "";
        status(reached + " reached: " + accepted + " accepted, " + review
                + " review, " + rejected + " rejected"
                + (unavailable + incomplete == 0 ? "" : "; " + unavailable
                        + " unavailable, " + incomplete + " incomplete") + labels
                + ". No class population was changed.", false);
    }

    private void setTab(int index, String title, List<EntityRef> entities,
                        ConfiguredGraphDiscoveryQuery.Result result, String type) {
        List<EntityRef> values = entities == null ? List.of() : entities;
        int shown = Math.min(values.size(), 1_000);
        List<Viewable> cards = values.stream().limit(shown).map(entity -> {
            DynamicViewable card = new DynamicViewable(entity.id(), result.label(entity));
            card.type(type); card.put("QID", entity.id());
            return (Viewable) card;
        }).toList();
        results.setTitleAt(index, title + " (" + values.size()
                + (shown < values.size() ? "; showing " + shown : "") + ")");
        results.setComponentAt(index, cards.isEmpty() ? empty("No " + title.toLowerCase() + " nodes.")
                : SearchableView.builder(cards).sample(cards.getFirst())
                        .mode(RenderingMode.CARD).columns(3).collapsible(false)
                        .valueLinker(WikidataLinks.valueLinker()).build());
    }

    private void updateRunEnabled() {
        run.setEnabled(runner != null && !runner.isRunning()
                && model.graphDiscoveryConfiguration() != null);
    }

    private static JComponent empty(String text) { return new JLabel("  " + text); }
    private static String message(Throwable error) {
        return error == null || error.getMessage() == null
                ? "Unknown error" : error.getMessage();
    }
}
