package wikidata.explore.tree;

import wikidata.explore.model.*;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Middle panel when selected item is a field.
 *
 * Normal UI answers:
 *   How do we fill this field?
 */
public class FieldSourcePanel extends JPanel {

    private GeneratedFieldModel field;

    private Consumer<Void> afterChange = v -> {};

    private final JLabel titleLabel = new JLabel("Field");

    private final JTextField fieldNameField = new JTextField(16);
    private final JComboBox<FieldType> typeBox = new JComboBox<>(FieldType.values());
    private final JTextField objectTypeField = new JTextField(14);
    private final JComboBox<FieldCardinality> shapeBox =
            new JComboBox<>(FieldCardinality.values());

    private final JTextField propertyPidField = new JTextField(10);
    private final JLabel propertyLabel = new JLabel("(not selected)");

    private final JSpinner limitSpinner =
            new JSpinner(new SpinnerNumberModel(50, 1, 10000, 10));

    private final JLabel recommendationLabel =
            new JLabel("Load method: Auto");

    private final JButton applyButton =
            new JButton("Apply field source");

    public FieldSourcePanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    public void afterChange(Consumer<Void> afterChange) {
        this.afterChange = afterChange == null ? v -> {} : afterChange;
    }

    public void edit(GeneratedFieldModel field) {
        this.field = field;

        if (field == null) {
            clear();
            return;
        }

        FieldSourceMapping m = field.mapping();

        titleLabel.setText("Field: " + field.name());

        fieldNameField.setText(field.name());
        typeBox.setSelectedItem(field.type());
        objectTypeField.setText(field.entityClassName());
        shapeBox.setSelectedItem(field.cardinality());

        propertyPidField.setText(m.propertyPid());
        propertyLabel.setText(m.displayProperty());

        limitSpinner.setValue(Math.max(1, m.limit()));

        updateRecommendation();
    }

    public void useProperty(String pid, String label) {
        if (field == null) {
            return;
        }

        field.mapping().propertyPid(pid);
        field.mapping().propertyLabel(label);

        propertyPidField.setText(field.mapping().propertyPid());
        propertyLabel.setText(field.mapping().displayProperty());

        autoAdjustFromProperty(pid, label);

        updateRecommendation();
        afterChange.accept(null);
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        int y = 0;

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        addWide(form, c, y++, titleLabel);

        JLabel question = new JLabel("How do we fill this field?");
        question.setFont(question.getFont().deriveFont(Font.ITALIC));
        addWide(form, c, y++, question);

        addRow(form, c, y++, "Field name:", fieldNameField);
        addRow(form, c, y++, "Type:", typeBox);
        addRow(form, c, y++, "Object type:", objectTypeField);
        addRow(form, c, y++, "Shape:", shapeBox);

        JPanel propRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        propRow.add(propertyPidField);
        propRow.add(propertyLabel);
        addRow(form, c, y++, "Wikidata property:", propRow);

        JPanel limitRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        limitRow.add(new JLabel("Limit per parent:"));
        limitRow.add(limitSpinner);
        addWide(form, c, y++, limitRow);

        addWide(form, c, y++, recommendationLabel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(applyButton);
        addWide(form, c, y++, buttons);

        add(scroll, BorderLayout.CENTER);

        applyButton.addActionListener(e -> apply());
        typeBox.addActionListener(e -> updateRecommendation());
        shapeBox.addActionListener(e -> updateRecommendation());
    }

    private void apply() {
        if (field == null) {
            return;
        }

        field.name(fieldNameField.getText());
        field.type((FieldType) typeBox.getSelectedItem());
        field.entityClassName(objectTypeField.getText());
        field.cardinality((FieldCardinality) shapeBox.getSelectedItem());

        FieldSourceMapping m = field.mapping();
        m.propertyPid(propertyPidField.getText());
        m.limit(((Number) limitSpinner.getValue()).intValue());

        autoProduction(m);
        propertyLabel.setText(m.displayProperty());

        titleLabel.setText("Field: " + field.name());

        updateRecommendation();
        afterChange.accept(null);
    }

    private void autoAdjustFromProperty(String pid, String label) {
        if (field == null) {
            return;
        }

        String cleanPid = RuleNode.cleanPid(pid);
        String lower = label == null ? "" : label.toLowerCase();

        if ("P18".equals(cleanPid) || lower.contains("image")) {
            field.type(FieldType.IMAGE);
            field.cardinality(FieldCardinality.SINGLE);
        }

        if (lower.contains("border")
                || lower.contains("neighbour")
                || lower.contains("neighbor")) {
            field.type(FieldType.ENTITY);
            field.entityClassName("Constellation");
            field.cardinality(FieldCardinality.COLLECTION);
        }

        typeBox.setSelectedItem(field.type());
        shapeBox.setSelectedItem(field.cardinality());
        objectTypeField.setText(field.entityClassName());

        autoProduction(field.mapping());
    }

    private void autoProduction(FieldSourceMapping m) {
        if (field == null) {
            return;
        }

        if (field.type() == FieldType.ENTITY && field.collection()) {
            m.productionKind(FieldProductionKind.DELAYED_ENTITY_FIELD);
        } else if (field.type() == FieldType.IMAGE) {
            m.productionKind(FieldProductionKind.INLINE_VALUE);
        } else {
            m.productionKind(FieldProductionKind.AUTO);
        }
    }

    private void updateRecommendation() {
        if (field == null) {
            recommendationLabel.setText(" ");
            return;
        }

        FieldType type = (FieldType) typeBox.getSelectedItem();
        FieldCardinality card = (FieldCardinality) shapeBox.getSelectedItem();

        String text;

        if (type == FieldType.ENTITY && card == FieldCardinality.COLLECTION) {
            text = "Load method: related entity values, loaded after root instances";
        } else if (type == FieldType.IMAGE) {
            text = "Load method: media field";
        } else {
            text = "Load method: Auto";
        }

        recommendationLabel.setText(text);
    }

    private void clear() {
        titleLabel.setText("Field");
        fieldNameField.setText("");
        objectTypeField.setText("");
        propertyPidField.setText("");
        propertyLabel.setText("(not selected)");
        recommendationLabel.setText(" ");
    }

    private static void addRow(
            JPanel form,
            GridBagConstraints c,
            int y,
            String label,
            JComponent comp) {

        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 1;
        c.weightx = 0;
        form.add(new JLabel(label), c);

        c.gridx = 1;
        c.weightx = 1;
        form.add(comp, c);
    }

    private static void addWide(
            JPanel form,
            GridBagConstraints c,
            int y,
            JComponent comp) {

        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(comp, c);
        c.gridwidth = 1;
    }
}
