package wikidata.explore.tree;

import wikidata.explore.filter.ValueFilterEditorPanel;
import wikidata.explore.ui.IncludedFieldEditorPanel;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

public class RuleNodeEditorPanel extends JPanel {

    private RuleNode node;

    private Runnable afterChange = () -> {};
    private Consumer<String> log = s -> {};

    private final JTextField nodeNameField  = new JTextField(12);
    private final JTextField itemVarField   = new JTextField(10);

    private final JTextField sourceQidField     = new JTextField(10);
    private final JTextField sourceLabelField   = new JTextField(14);
    private final JTextField propertyPidField   = new JTextField(8);
    private final JTextField propertyLabelField = new JTextField(14);

    private final JComboBox<RuleDirection> directionBox =
            new JComboBox<>(RuleDirection.values());

    private final JLabel directionPreviewLabel = new JLabel(" ");

    private final JSpinner limitSpinner =
            new JSpinner(new SpinnerNumberModel(200, 1, 10000, 10));

    private final JCheckBox requireLabelBox =
            new JCheckBox("Require label", true);

    private final JTextField languageField = new JTextField(4);

    private final JTextField includedQidsField              = new JTextField(32);
    private final JTextField excludedQidsField              = new JTextField(32);
    private final JTextField excludedPredicateObjectField   = new JTextField(32);

    private final ValueFilterEditorPanel valueFilterEditor =
            new ValueFilterEditorPanel();

    private final IncludedFieldEditorPanel includedFieldEditor =
            new IncludedFieldEditorPanel();

    private final JTextField childFieldNameField = new JTextField(12);
    private final JCheckBox  childCollectionBox  = new JCheckBox("collection", true);
    private final JButton    addChildButton      = new JButton("Add child edge");

    public RuleNodeEditorPanel() {
        super(new BorderLayout(6, 6));
        buildUi();
        wireLightweightPreviewUpdates();
    }

    public RuleNode currentNode() {
        return node;
    }

    public void applyCurrentNodeEdits() {
        applyNodeEdits();
    }

    public void afterChange(Runnable afterChange) {
        this.afterChange = afterChange == null ? () -> {} : afterChange;
    }

    public void log(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
    }

    public void editNode(RuleNode node) {
        this.node = node;

        if (node == null) {
            clearEditor();
            return;
        }

        nodeNameField.setText(node.name());
        itemVarField.setText(node.itemVar());

        sourceQidField.setText(node.sourceQid());
        sourceLabelField.setText(node.sourceLabel());

        propertyPidField.setText(node.propertyPid());
        propertyLabelField.setText(node.propertyLabel());

        directionBox.setSelectedItem(node.direction());
        limitSpinner.setValue(Math.max(1, node.limit()));

        RuleLabelConfig lc = node.labelConfig();
        requireLabelBox.setSelected(lc.requireLabel());
        languageField.setText(lc.anyLanguage() ? "" : lc.language());

        includedQidsField.setText(node.includedQidsText());
        excludedQidsField.setText(node.excludedQidsText());
        excludedPredicateObjectField.setText(node.excludedPredicateObjectsText());

        valueFilterEditor.setFilters(node.valueFilters());
        includedFieldEditor.setFields(node.includedFields());

        updateDirectionPreview();
    }

    public void useProperty(WikidataPropertyQuizable property) {
        if (property == null) {
            return;
        }

        useProperty(property.pid(), property.getName());
    }

    public void useProperty(String pid, String label) {
        propertyPidField.setText(RuleNode.cleanPid(pid));
        propertyLabelField.setText(label == null ? "" : label);

        valueFilterEditor.useProperty(pid, label);
        includedFieldEditor.useProperty(pid, label);

        updateDirectionPreview();
    }

    public void useSourceQid(String qid, String label) {
        applyNodeEdits();

        if (node == null) {
            return;
        }

        node.sourceQid(qid);
        node.sourceLabel(label);

        sourceQidField.setText(node.sourceQid());
        sourceLabelField.setText(node.sourceLabel());

        updateDirectionPreview();
        afterChange.run();
    }

    public void addIncludedQids(Collection<String> qids) {
        applyNodeEdits();

        if (node == null || qids == null) {
            return;
        }

        for (String qid : qids) {
            node.addIncludedQid(qid);
        }

        includedQidsField.setText(node.includedQidsText());

        afterChange.run();
    }

    public void replaceIncludedQids(Collection<String> qids) {
        applyNodeEdits();

        if (node == null) {
            return;
        }

        node.includedQids().clear();

        if (qids != null) {
            for (String qid : qids) {
                node.addIncludedQid(qid);
            }
        }

        includedQidsField.setText(node.includedQidsText());

        afterChange.run();
    }

    public void addExcludedQids(Collection<String> qids) {
        applyNodeEdits();

        if (node == null || qids == null) {
            return;
        }

        for (String qid : qids) {
            node.addExcludedQid(qid);
        }

        excludedQidsField.setText(node.excludedQidsText());

        afterChange.run();
    }

    public void addChildEdgeFromProperty(
            String pid,
            String label,
            String fieldName,
            boolean collection) {

        applyNodeEdits();

        if (node == null) {
            return;
        }

        RuleNode child =
                new RuleNode(fieldName, fieldName);

        child.sourceQid(node.sourceQid());
        child.sourceLabel(node.sourceLabel());
        child.propertyPid(pid);
        child.propertyLabel(label);
        child.direction(RuleDirection.ROOT_TO_ITEM);
        child.limit(200);
        child.labelConfig(node.labelConfig());

        RuleEdge edge =
                new RuleEdge(fieldName, child, collection);

        node.edges().add(edge);

        log.accept("Added child edge from discovered property: "
                           + edge.displayName()
                           + " / "
                           + label
                           + " ("
                           + pid
                           + ")\n");

        afterChange.run();
    }

    public void addIncludedFieldFromProperty(
            String pid,
            String label,
            String fieldName,
            NodePropertyDiscoveryPanel.PropertyKind kind) {

        applyNodeEdits();

        if (node == null) {
            return;
        }

        RuleIncludedField.FieldKind fieldKind =
                switch (kind) {
                    case MEDIA -> RuleIncludedField.FieldKind.MEDIA;
                    case ENTITY -> RuleIncludedField.FieldKind.ENTITY;
                    case SCALAR -> RuleIncludedField.FieldKind.AUTO;
                };

        node.includedFields().add(new RuleIncludedField(
                fieldName,
                pid,
                label,
                fieldKind,
                true));

        includedFieldEditor.setFields(node.includedFields());

        log.accept("Added included field from discovered property: "
                           + fieldName
                           + " / "
                           + label
                           + " ("
                           + pid
                           + ")\n");

        afterChange.run();
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets  = new Insets(3, 3, 3, 3);
        c.anchor  = GridBagConstraints.WEST;
        c.fill    = GridBagConstraints.HORIZONTAL;
        int y = 0;

        JPanel identityRow = rowPanel();
        identityRow.add(new JLabel("Node:"));
        identityRow.add(nodeNameField);
        identityRow.add(new JLabel("var:"));
        identityRow.add(itemVarField);
        addWideRow(form, c, y++, identityRow);

        JPanel sourceRow = rowPanel();
        sourceRow.add(new JLabel("Source QID:"));
        sourceRow.add(sourceQidField);
        sourceRow.add(new JLabel("label:"));
        sourceRow.add(sourceLabelField);
        addWideRow(form, c, y++, sourceRow);

        JPanel propertyRow = rowPanel();
        propertyRow.add(new JLabel("Property PID:"));
        propertyRow.add(propertyPidField);
        propertyRow.add(new JLabel("label:"));
        propertyRow.add(propertyLabelField);
        addWideRow(form, c, y++, propertyRow);

        JPanel directionRow = rowPanel();
        directionRow.add(new JLabel("Direction:"));
        directionRow.add(directionBox);
        directionRow.add(new JLabel("Meaning:"));
        directionPreviewLabel.setFont(
                directionPreviewLabel.getFont().deriveFont(Font.BOLD));
        directionRow.add(directionPreviewLabel);
        addWideRow(form, c, y++, directionRow);

        JPanel optionsRow = rowPanel();
        optionsRow.add(new JLabel("Limit:"));
        optionsRow.add(limitSpinner);
        optionsRow.add(requireLabelBox);
        optionsRow.add(new JLabel("lang:"));
        languageField.setToolTipText("Language code, e.g. en, hu, de — blank means any");
        optionsRow.add(languageField);
        addWideRow(form, c, y++, optionsRow);

        addSeparator(form, c, y++);

        addWideRow(form, c, y++, valueFilterEditor);

        addSeparator(form, c, y++);

        addWideRow(form, c, y++, includedFieldEditor);

        addSeparator(form, c, y++);

        addRow(form, c, y++, "Include exact QIDs:", includedQidsField);
        addRow(form, c, y++, "Exclude exact QIDs:", excludedQidsField);
        addRow(form, c, y++, "Exclude predicate/object:", excludedPredicateObjectField);

        JLabel hint = new JLabel("format: P31:Q5 P31:Q4167410  (predicate:object)");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC));
        addWideRow(form, c, y++, hint);

        addSeparator(form, c, y++);

        JPanel childRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        childRow.add(new JLabel("New child field:"));
        childRow.add(childFieldNameField);
        childRow.add(childCollectionBox);
        childRow.add(addChildButton);
        addWideRow(form, c, y++, childRow);

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        form.setMinimumSize(form.getPreferredSize());

        add(scroll, BorderLayout.CENTER);

        addChildButton.addActionListener(e -> addChildNode());
    }

    private void wireLightweightPreviewUpdates() {
        directionBox.addActionListener(e -> updateDirectionPreview());
        nodeNameField.addActionListener(e -> updateDirectionPreview());
        sourceLabelField.addActionListener(e -> updateDirectionPreview());
        propertyLabelField.addActionListener(e -> updateDirectionPreview());
    }

    private void applyNodeEdits() {
        if (node == null) {
            return;
        }

        node.name(nodeNameField.getText());
        node.itemVar(itemVarField.getText());

        node.sourceQid(sourceQidField.getText());
        node.sourceLabel(sourceLabelField.getText());

        node.propertyPid(propertyPidField.getText());
        node.propertyLabel(propertyLabelField.getText());

        Object dir = directionBox.getSelectedItem();

        if (dir instanceof RuleDirection d) {
            node.direction(d);
        }

        node.limit(((Number) limitSpinner.getValue()).intValue());

        String lang = languageField.getText().trim();

        node.labelConfig(new RuleLabelConfig(
                requireLabelBox.isSelected(),
                lang.isBlank() ? "any" : lang));

        node.includedQidsText(includedQidsField.getText());
        node.excludedQidsText(excludedQidsField.getText());
        node.excludedPredicateObjectsText(excludedPredicateObjectField.getText());

        node.valueFilters().clear();
        node.valueFilters().addAll(valueFilterEditor.filters());

        node.includedFields().clear();
        node.includedFields().addAll(includedFieldEditor.fields());

        updateDirectionPreview();
        afterChange.run();
    }

    private void addChildNode() {
        applyNodeEdits();

        if (node == null) {
            return;
        }

        String fieldName = childFieldNameField.getText().trim();

        if (fieldName.isBlank()) {
            fieldName = "child";
        }

        RuleNode child = new RuleNode(fieldName, fieldName);
        child.sourceQid(node.sourceQid());
        child.sourceLabel(node.sourceLabel());
        child.propertyPid(node.propertyPid());
        child.propertyLabel(node.propertyLabel());
        child.direction(node.direction());
        child.limit(node.limit());
        child.labelConfig(node.labelConfig());

        RuleEdge edge = new RuleEdge(
                fieldName,
                child,
                childCollectionBox.isSelected());

        node.edges().add(edge);
        childFieldNameField.setText("");

        log.accept("Added child edge: " + edge.displayName() + "\n");
        afterChange.run();
    }

    private void updateDirectionPreview() {
        Object dir = directionBox.getSelectedItem();

        String source =
                blankTo(sourceLabelField.getText(), sourceQidField.getText());

        String property =
                blankTo(propertyLabelField.getText(), propertyPidField.getText());

        String nodeName =
                blankTo(nodeNameField.getText(), "item");

        if (dir instanceof RuleDirection d) {
            directionPreviewLabel.setText(d.previewText(nodeName, property, source));
        } else {
            directionPreviewLabel.setText(" ");
        }
    }

    private void clearEditor() {
        nodeNameField.setText("");
        itemVarField.setText("");
        sourceQidField.setText("");
        sourceLabelField.setText("");
        propertyPidField.setText("");
        propertyLabelField.setText("");
        includedQidsField.setText("");
        excludedQidsField.setText("");
        excludedPredicateObjectField.setText("");
        languageField.setText("");
        requireLabelBox.setSelected(true);
        valueFilterEditor.setFilters(List.of());
        includedFieldEditor.setFields(List.of());
        directionPreviewLabel.setText(" ");
    }

    private static JPanel rowPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setMinimumSize(new Dimension(10, 28));
        p.setPreferredSize(new Dimension(10, 30));
        return p;
    }

    private static void addRow(JPanel form, GridBagConstraints c,
                               int y, String label, JComponent component) {
        c.gridx = 0;
        c.gridy = y;
        c.weightx = 0.0;
        c.gridwidth = 1;
        form.add(new JLabel(label), c);

        c.gridx = 1;
        c.weightx = 1.0;
        c.gridwidth = 1;
        form.add(component, c);
    }

    private static void addWideRow(JPanel form, GridBagConstraints c,
                                   int y, JComponent component) {
        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 2;
        c.weightx = 1.0;
        form.add(component, c);
        c.gridwidth = 1;
    }

    private static void addSeparator(JPanel form, GridBagConstraints c, int y) {
        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 2;
        c.weightx = 1.0;
        form.add(new JSeparator(), c);
        c.gridwidth = 1;
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }
}
