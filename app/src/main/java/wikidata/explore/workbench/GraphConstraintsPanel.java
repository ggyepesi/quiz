package wikidata.explore.workbench;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.GraphEvidenceCondition;
import datasource.graph.constraint.GraphNodeCondition;
import datasource.graph.constraint.GraphPath;
import datasource.graph.constraint.GraphRelationAbsent;
import datasource.graph.constraint.GraphRelationExists;
import datasource.graph.constraint.GraphRelationReaches;
import wikidata.WikidataIds;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.RuleDirection;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A deliberately small editor for the persisted graph admission condition.
 *
 * <p>This is a graph configuration surface, not a field editor. Existing fields are
 * offered as convenient names for their relations, while entering a PID keeps an
 * evidence edge unstored. Both routes produce the same {@link GraphEvidenceCondition}.
 */
final class GraphConstraintsPanel extends JPanel {
    private static final String PROVIDER = "wikidata";

    private enum TestKind {
        HAS_VALUE("has a value"),
        REACHES_ENTITY("reaches this entity"),
        HAS_NO_VALUE("has no value");

        private final String label;
        TestKind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private record RelationChoice(String owner, String field, String label, String pid) {
        @Override public String toString() {
            String fieldName = owner.isBlank() ? field : owner + "." + field;
            String relation = label.isBlank() || label.equals(pid)
                    ? pid : label + " (" + pid + ")";
            return fieldName + " — " + relation;
        }
    }

    private final GeneratedProjectModel model;
    private final JComboBox<GeneratedClassModel> classBox = new JComboBox<>();
    private final JTextField nameField = new JTextField(24);
    private final JComboBox<RelationChoice> evidenceFieldBox = new JComboBox<>();
    private final JTextField evidencePidField = new JTextField(8);
    private final DefaultListModel<GraphPath> evidenceModel = new DefaultListModel<>();
    private final JList<GraphPath> evidenceList = new JList<>(evidenceModel);
    private final JComboBox<RelationChoice> testFieldBox = new JComboBox<>();
    private final JTextField testPidField = new JTextField(8);
    private final JComboBox<TestKind> testKindBox = new JComboBox<>(TestKind.values());
    private final JTextField testEntityField = new JTextField(9);
    private final DefaultListModel<GraphNodeCondition> testsModel = new DefaultListModel<>();
    private final JList<GraphNodeCondition> testsList = new JList<>(testsModel);
    private final JComboBox<GraphEvidenceCondition.ReviewDisposition> reviewBox =
            new JComboBox<>(GraphEvidenceCondition.ReviewDisposition.values());
    private final JLabel status = new JLabel(" ");
    { status.setName("graph.status"); }
    private Consumer<Void> afterChange = ignored -> {};
    private boolean loading;

    GraphConstraintsPanel(GeneratedProjectModel model) {
        super(new BorderLayout(6, 6));
        this.model = java.util.Objects.requireNonNull(model, "model");
        buildUi();
        refreshClasses();
    }

    void afterChange(Consumer<Void> value) {
        afterChange = value == null ? ignored -> {} : value;
    }

    void refresh() {
        GeneratedClassModel selected = selectedClass();
        refreshClasses();
        if (selected != null) classBox.setSelectedItem(model.findClass(selected.className()));
        loadSelectedClass();
    }

    /**
     * The condition is authored and persisted, but nothing reads it during generation
     * yet — #184 wires the provider adapter into population assembly. Every line that
     * reports a stored condition says so, because otherwise a modeller configures one,
     * regenerates, and finds the population unchanged with nothing explaining why.
     * Delete this the moment generation applies the condition.
     */
    private static final String NOT_YET_ENFORCED =
            " Stored only — generation does not apply it yet (#184).";

    void applyEdits() {
        GeneratedClassModel owner = selectedClass();
        if (owner == null) return;
        try {
            if (evidenceModel.isEmpty() && testsModel.isEmpty()
                    && nameField.getText().isBlank()) {
                owner.graphAdmissionCondition(null);
                status("No population constraint configured.", false);
            } else {
                GraphEvidenceCondition condition = new GraphEvidenceCondition(
                        nameField.getText(), elements(evidenceModel), elements(testsModel),
                        (GraphEvidenceCondition.ReviewDisposition) reviewBox.getSelectedItem());
                owner.graphAdmissionCondition(condition);
                status("Applied to " + owner.className() + " population."
                        + NOT_YET_ENFORCED, false);
            }
            afterChange.accept(null);
        } catch (IllegalArgumentException ex) {
            status(ex.getMessage(), true);
        }
    }

    private void buildUi() {
        classBox.setName("graph.populationClass");
        nameField.setName("graph.conditionName");
        evidenceFieldBox.setName("graph.evidenceField");
        evidencePidField.setName("graph.evidenceProperty");
        testFieldBox.setName("graph.testField");
        testPidField.setName("graph.testProperty");
        testKindBox.setName("graph.testKind");
        testEntityField.setName("graph.testEntity");
        reviewBox.setName("graph.reviewDisposition");
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        int y = 0;

        JLabel intro = new JLabel("Admit population nodes using evidence reached through graph relations.");
        intro.setFont(intro.getFont().deriveFont(Font.ITALIC));
        addWide(form, c, y++, intro);
        addRow(form, c, y++, "Population class:", classBox);
        addRow(form, c, y++, "Condition name:", nameField);

        JLabel evidenceHeader = new JLabel(
                "Relations from each candidate to evidence (one or more may match)");
        evidenceHeader.setFont(evidenceHeader.getFont().deriveFont(Font.BOLD));
        addWide(form, c, y++, evidenceHeader);
        addWide(form, c, y++, new JLabel(
                "Choose a configured field for its relation, or enter a property without storing it."));
        JPanel evidenceAdd = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        evidenceFieldBox.setPrototypeDisplayValue(new RelationChoice(
                "Position", "jurisdiction", "jurisdiction", "P1001"));
        evidenceAdd.add(evidenceFieldBox);
        evidenceAdd.add(new JLabel("Property:"));
        evidenceAdd.add(evidencePidField);
        JButton addEvidence = new JButton("Add evidence edge");
        evidenceAdd.add(addEvidence);
        addWide(form, c, y++, evidenceAdd);
        evidenceList.setVisibleRowCount(4);
        evidenceList.setCellRenderer(relationRenderer());
        addWide(form, c, y++, listWithRemove(evidenceList, evidenceModel));

        JLabel testsHeader = new JLabel(
                "Tests on each reached evidence node (one or more may match)");
        testsHeader.setFont(testsHeader.getFont().deriveFont(Font.BOLD));
        addWide(form, c, y++, testsHeader);
        JPanel testAdd = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        testFieldBox.setPrototypeDisplayValue(new RelationChoice(
                "Polity", "dissolved", "date of official closure", "P576"));
        testAdd.add(testFieldBox);
        testAdd.add(new JLabel("Property:"));
        testAdd.add(testPidField);
        testAdd.add(testKindBox);
        testAdd.add(testEntityField);
        JButton addTest = new JButton("Add test");
        testAdd.add(addTest);
        addWide(form, c, y++, testAdd);
        testsList.setVisibleRowCount(4);
        testsList.setCellRenderer(conditionRenderer());
        addWide(form, c, y++, listWithRemove(testsList, testsModel));

        reviewBox.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value == GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT) {
                    setText("include and report");
                } else if (value != null) {
                    setText("exclude and report");
                }
                return this;
            }
        });
        addRow(form, c, y++, "When evidence cannot decide:", reviewBox);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton clear = new JButton("Clear draft");
        JButton apply = new JButton("Apply graph constraint");
        actions.add(clear);
        actions.add(apply);
        addWide(form, c, y++, actions);
        addWide(form, c, y, status);

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        classBox.addActionListener(e -> { if (!loading) loadSelectedClass(); });
        evidenceFieldBox.addActionListener(e -> copyPid(evidenceFieldBox, evidencePidField));
        testFieldBox.addActionListener(e -> copyPid(testFieldBox, testPidField));
        testKindBox.addActionListener(e -> updateTestEntityState());
        addEvidence.addActionListener(e -> addEvidence());
        addTest.addActionListener(e -> addTest());
        apply.addActionListener(e -> applyEdits());
        clear.addActionListener(e -> clearDraft());
        updateTestEntityState();
    }

    private void refreshClasses() {
        loading = true;
        try {
            Object selected = classBox.getSelectedItem();
            classBox.removeAllItems();
            for (GeneratedClassModel clazz : model.classes()) {
                if (clazz != null && !clazz.isImported()
                        && clazz.classKind() == wikidata.explore.model.ClassKind.SOURCE) {
                    classBox.addItem(clazz);
                }
            }
            if (selected instanceof GeneratedClassModel clazz) {
                GeneratedClassModel current = model.findClass(clazz.className());
                if (current != null) classBox.setSelectedItem(current);
            }
        } finally {
            loading = false;
        }
    }

    private void loadSelectedClass() {
        loading = true;
        try {
            GeneratedClassModel owner = selectedClass();
            GraphEvidenceCondition condition = owner == null
                    ? null : owner.graphAdmissionCondition();
            nameField.setText(condition == null ? "" : condition.name());
            replace(evidenceModel, condition == null ? List.of() : condition.evidencePaths());
            replace(testsModel, condition == null ? List.of() : condition.tests());
            reviewBox.setSelectedItem(condition == null
                    ? GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT
                    : condition.reviewDisposition());
            refreshFieldChoices(owner);
            status(condition == null ? "No population constraint configured."
                    : "Configured; edit the draft and Apply to change it."
                            + NOT_YET_ENFORCED, false);
        } finally {
            loading = false;
        }
    }

    private void refreshFieldChoices(GeneratedClassModel owner) {
        evidenceFieldBox.removeAllItems();
        testFieldBox.removeAllItems();
        if (owner != null) addFieldChoices(
                evidenceFieldBox, owner, owner.fields(), "", true);
        for (GeneratedClassModel clazz : model.classes()) {
            if (clazz != null) addFieldChoices(
                    testFieldBox, clazz, clazz.fields(), "", false);
        }
        evidencePidField.setText("");
        testPidField.setText("");
    }

    private static void addFieldChoices(JComboBox<RelationChoice> box,
            GeneratedClassModel owner, List<GeneratedFieldModel> fields, String prefix,
            boolean entityOnly) {
        for (GeneratedFieldModel field : fields) {
            if (field == null) continue;
            String path = prefix.isBlank() ? field.name() : prefix + "." + field.name();
            String pid = field.mapping().propertyPid();
            boolean entityRelation = field.type() == datasource.schema.FieldType.ENTITY;
            if (WikidataIds.isPid(pid)
                    && field.mapping().direction() == RuleDirection.ROOT_TO_ITEM
                    && (!entityOnly || entityRelation)) {
                box.addItem(new RelationChoice(owner.className(), path,
                        field.mapping().propertyLabel(), pid));
            }
            addFieldChoices(box, owner, field.fields(), path, entityOnly);
        }
    }

    private void addEvidence() {
        String pid = cleanPid(evidencePidField.getText());
        if (!WikidataIds.isPid(pid)) {
            status("Choose a field or enter a Wikidata property PID for the evidence edge.", true);
            return;
        }
        GraphPath path = GraphPath.direct(new GraphRelation(PROVIDER, pid),
                GraphTraversalDirection.OUTGOING);
        if (!elements(evidenceModel).contains(path)) evidenceModel.addElement(path);
        evidencePidField.setText("");
        status("Draft changed; Apply graph constraint to save it.", false);
    }

    private void addTest() {
        String pid = cleanPid(testPidField.getText());
        if (!WikidataIds.isPid(pid)) {
            status("Choose a field or enter a Wikidata property PID for the test.", true);
            return;
        }
        GraphRelation relation = new GraphRelation(PROVIDER, pid);
        TestKind kind = (TestKind) testKindBox.getSelectedItem();
        GraphNodeCondition condition;
        if (kind == TestKind.REACHES_ENTITY) {
            String qid = cleanQid(testEntityField.getText());
            if (!WikidataIds.isQid(qid)) {
                status("Enter the QID which the test property must reach.", true);
                return;
            }
            condition = new GraphRelationReaches(relation,
                    GraphTraversalDirection.OUTGOING, EntityRef.wikidata(qid));
        } else if (kind == TestKind.HAS_NO_VALUE) {
            condition = new GraphRelationAbsent(relation, GraphTraversalDirection.OUTGOING);
        } else {
            condition = new GraphRelationExists(relation, GraphTraversalDirection.OUTGOING);
        }
        if (!elements(testsModel).contains(condition)) testsModel.addElement(condition);
        testPidField.setText("");
        testEntityField.setText("");
        status("Draft changed; Apply graph constraint to save it.", false);
    }

    private void clearDraft() {
        nameField.setText("");
        evidenceModel.clear();
        testsModel.clear();
        reviewBox.setSelectedItem(GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT);
        status("Draft cleared; Apply graph constraint to remove the saved condition.", false);
    }

    private void updateTestEntityState() {
        boolean enabled = testKindBox.getSelectedItem() == TestKind.REACHES_ENTITY;
        testEntityField.setEnabled(enabled);
        testEntityField.setToolTipText(enabled
                ? "Required target QID, for example Q3024240 (dissolved state)."
                : "Used only by ‘reaches this entity’. ");
    }

    private static <T> JPanel listWithRemove(JList<T> list, DefaultListModel<T> model) {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        JButton remove = new JButton("Remove selected");
        remove.addActionListener(e -> {
            int[] selected = list.getSelectedIndices();
            for (int i = selected.length - 1; i >= 0; i--) model.remove(selected[i]);
        });
        JPanel button = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        button.add(remove);
        panel.add(button, BorderLayout.SOUTH);
        return panel;
    }

    private static ListCellRenderer<Object> relationRenderer() {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof GraphPath path) {
                    setText("candidate → " + path.relation().relationId() + " → evidence");
                }
                return this;
            }
        };
    }

    private static ListCellRenderer<Object> conditionRenderer() {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof GraphRelationExists exists) {
                    setText("evidence → " + exists.relation().relationId() + " → has a value");
                } else if (value instanceof GraphRelationAbsent absent) {
                    setText("evidence → " + absent.relation().relationId() + " → has no value");
                } else if (value instanceof GraphRelationReaches reaches) {
                    setText("evidence → " + reaches.relation().relationId() + " → "
                            + reaches.entity().id());
                }
                return this;
            }
        };
    }

    private static void copyPid(JComboBox<RelationChoice> box, JTextField target) {
        if (box.getSelectedItem() instanceof RelationChoice choice) target.setText(choice.pid());
    }

    private GeneratedClassModel selectedClass() {
        return classBox.getSelectedItem() instanceof GeneratedClassModel clazz ? clazz : null;
    }

    private void status(String text, boolean error) {
        status.setText(text == null || text.isBlank() ? " " : text);
        status.setForeground(error ? new Color(165, 40, 35) : UIManager.getColor("Label.foreground"));
    }

    private static String cleanPid(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static String cleanQid(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static <T> List<T> elements(DefaultListModel<T> model) {
        List<T> values = new ArrayList<>(model.size());
        for (int i = 0; i < model.size(); i++) values.add(model.get(i));
        return List.copyOf(values);
    }

    private static <T> void replace(DefaultListModel<T> model, List<T> values) {
        model.clear();
        if (values != null) values.forEach(model::addElement);
    }

    private static void addRow(JPanel panel, GridBagConstraints c, int y,
            String label, Component value) {
        c.gridy = y;
        c.gridx = 0;
        c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(value, c);
    }

    private static void addWide(JPanel panel, GridBagConstraints c, int y, Component value) {
        c.gridy = y;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        panel.add(value, c);
        c.gridwidth = 1;
    }
}
