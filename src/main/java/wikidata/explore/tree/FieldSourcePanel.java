package wikidata.explore.tree;

import wikidata.explore.WikidataProperty;
import wikidata.explore.WikidataPropertyScore;
import wikidata.explore.model.*;

import javax.swing.*;
import java.awt.*;
import java.util.Map;
import java.util.function.Consumer;

public class FieldSourcePanel extends JPanel {

    private static final String NEW_CLASS_SENTINEL = "New class...";

    private GeneratedFieldModel field;
    private GeneratedProjectModel projectModel;

    private Consumer<Void> afterChange = v -> {};
    private Consumer<GeneratedFieldModel> afterApplyField = f -> {};
    private Runnable onSampleRequested = () -> {};

    private Map<String, WikidataProperty> propertyCache = Map.of();
    private boolean updatingObjectTypeBox = false;

    private final JLabel titleLabel = new JLabel("Field");

    private final JTextField fieldNameField = new JTextField(16);
    private final JComboBox<FieldType> typeBox =
            new JComboBox<>(FieldType.values());
    private final JComboBox<String> objectTypeBox = new JComboBox<>();
    private final JComboBox<FieldCardinality> shapeBox =
            new JComboBox<>(FieldCardinality.values());
    private final JComboBox<FieldRenderMode> renderModeBox =
            new JComboBox<>(FieldRenderMode.values());

    private final JTextField propertyPidField = new JTextField(10);
    private final JLabel propertyLabel = new JLabel("(not selected)");

    private final JSpinner limitSpinner =
            new JSpinner(new SpinnerNumberModel(50, 1, 10000, 10));

    private final JLabel recommendationLabel =
            new JLabel("Load method: Auto");

    private final JButton sampleShapeButton =
            new JButton("Sample to set shape");

    private final JButton applyButton =
            new JButton("Apply field source");

    private final JComboBox<FieldSourceType> sourceTypeBox =
            new JComboBox<>(FieldSourceType.values());

    public FieldSourcePanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    public void afterChange(Consumer<Void> afterChange) {
        this.afterChange = afterChange == null ? v -> {} : afterChange;
    }

    public void setPropertyCache(Map<String, WikidataProperty> cache) {
        this.propertyCache = cache == null ? Map.of() : cache;
    }

    public void setProjectModel(GeneratedProjectModel projectModel) {
        this.projectModel = projectModel;
    }

    public void onSampleRequested(Runnable r) {
        this.onSampleRequested = r == null ? () -> {} : r;
    }

    public void afterApplyField(Consumer<GeneratedFieldModel> afterApplyField) {
        this.afterApplyField =
                afterApplyField == null ? f -> {} : afterApplyField;
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
        sourceTypeBox.setSelectedItem(m.sourceType());
        typeBox.setSelectedItem(field.type());
        refreshObjectTypeBox(entityClassForDisplay());
        shapeBox.setSelectedItem(field.cardinality());
        renderModeBox.setSelectedItem(field.renderMode());

        propertyPidField.setText(m.propertyPid());
        propertyLabel.setText(m.displayProperty());

        limitSpinner.setValue(Math.max(1, m.limit()));

        updateRecommendation();
        updateSampleButtonState();
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
        afterApplyField.accept(field);
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
        addRow(form, c, y++, "Source:", sourceTypeBox);
        addRow(form, c, y++, "Type:", typeBox);
        addRow(form, c, y++, "Object type:", objectTypeBox);
        addRow(form, c, y++, "Shape:", shapeBox);
        addRow(form, c, y++, "Render mode:", renderModeBox);

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
        buttons.add(sampleShapeButton);
        buttons.add(applyButton);
        addWide(form, c, y++, buttons);

        add(scroll, BorderLayout.CENTER);

        applyButton.addActionListener(e -> apply());
        sampleShapeButton.addActionListener(e -> onSampleRequested.run());
        sourceTypeBox.addActionListener(e -> updateRecommendation());
        objectTypeBox.addActionListener(e -> {
            if (updatingObjectTypeBox) {
                return;
            }
            if (NEW_CLASS_SENTINEL.equals(objectTypeBox.getSelectedItem())) {
                String name = JOptionPane.showInputDialog(
                        this, "New class name:", "Add class",
                        JOptionPane.PLAIN_MESSAGE);
                if (name != null && !name.isBlank()) {
                    name = GeneratedQuizableSourceGenerator.sanitizeClassName(name.trim());
                    if (projectModel != null) {
                        projectModel.addClass(new GeneratedClassModel(name));
                    }
                    refreshObjectTypeBox(name);
                } else {
                    refreshObjectTypeBox(
                            field != null ? field.entityClassName() : "");
                }
            }
        });

        typeBox.addActionListener(e -> {
            updateRecommendation();
            if (typeBox.getSelectedItem() == FieldType.ENTITY
                    && selectedEntityClass().isBlank()) {
                String propLabel = field != null && field.mapping() != null
                        ? field.mapping().propertyLabel() : "";
                refreshObjectTypeBox(suggestEntityClassName(
                        propLabel == null ? "" : propLabel.toLowerCase()));
            }
        });
        shapeBox.addActionListener(e -> {
            updateRecommendation();
            updateSampleButtonState();
        });
        renderModeBox.addActionListener(e -> updateRecommendation());
    }

    private void apply() {
        if (field == null) {
            return;
        }

        FieldSourceMapping m = field.mapping();
        m.sourceType((FieldSourceType) sourceTypeBox.getSelectedItem());
        m.propertyPid(propertyPidField.getText());
        m.limit(((Number) limitSpinner.getValue()).intValue());

        if (typeBox.getSelectedItem() == FieldType.AUTO) {
            autoAdjustFromProperty(m.propertyPid(), m.propertyLabel());
        }

        field.name(fieldNameField.getText());
        field.type((FieldType) typeBox.getSelectedItem());
        field.entityClassName(selectedEntityClass());
        field.cardinality((FieldCardinality) shapeBox.getSelectedItem());
        field.renderMode((FieldRenderMode) renderModeBox.getSelectedItem());

        autoProduction(m);
        propertyLabel.setText(m.displayProperty());

        titleLabel.setText("Field: " + field.name());

        updateRecommendation();

        afterChange.accept(null);
        afterApplyField.accept(field);
    }

    private void autoAdjustFromProperty(String pid, String label) {
        if (field == null) {
            return;
        }

        String cleanPid = RuleNode.cleanPid(pid);

        WikidataProperty cached = propertyCache.get(cleanPid);
        if (cached != null && !cached.datatype().isBlank()) {
            field.type(WikidataPropertyScore.fieldType(cached));
            field.cardinality(WikidataPropertyScore.fieldCardinality(cached));
            field.renderMode(WikidataPropertyScore.renderMode(cached));
            if (label == null || label.isBlank()) {
                label = cached.label();
            }
        }

        String lower = label == null ? "" : label.toLowerCase();

        if ("P18".equals(cleanPid) || lower.contains("image")) {
            field.type(FieldType.IMAGE);
            field.cardinality(FieldCardinality.SINGLE);
            field.renderMode(FieldRenderMode.INLINE);
        }

        if (field.type() == FieldType.ENTITY && field.entityClassName().isBlank()) {
            field.entityClassName(suggestEntityClassName(lower));
        }

        typeBox.setSelectedItem(field.type());
        shapeBox.setSelectedItem(field.cardinality());
        renderModeBox.setSelectedItem(field.renderMode());
        refreshObjectTypeBox(field.entityClassName());

        updateSampleButtonState();
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
        FieldSourceType sourceType =
                (FieldSourceType) sourceTypeBox.getSelectedItem();

        if (sourceType != null && !sourceType.implementedNow()) {
            recommendationLabel.setText(
                    "Source: " + sourceType + " | Not yet implemented");
            return;
        }

        FieldType type = (FieldType) typeBox.getSelectedItem();
        FieldCardinality card =
                (FieldCardinality) shapeBox.getSelectedItem();
        FieldRenderMode renderMode =
                (FieldRenderMode) renderModeBox.getSelectedItem();

        String text;

        if (type == FieldType.ENTITY && card == FieldCardinality.COLLECTION) {
            text = "Load method: related entity values, loaded after root instances";
        } else if (type == FieldType.IMAGE) {
            text = "Load method: media field";
        } else {
            text = "Load method: Auto";
        }

        if (renderMode == FieldRenderMode.REFERENCE) {
            text += " | Render: clickable references";
        } else if (renderMode == FieldRenderMode.INLINE) {
            text += " | Render: inline";
        }

        recommendationLabel.setText(text);
    }

    private void updateSampleButtonState() {
        boolean autoShape = shapeBox.getSelectedItem() == FieldCardinality.AUTO;
        sampleShapeButton.setVisible(autoShape);
    }

    private String entityClassForDisplay() {
        if (field == null) {
            return "";
        }
        String cls = field.entityClassName();
        if (!cls.isBlank()) {
            return cls;
        }
        if (field.type() != FieldType.ENTITY) {
            return "";
        }
        String propLabel = field.mapping() == null ? "" : field.mapping().propertyLabel();
        return suggestEntityClassName(propLabel == null ? "" : propLabel.toLowerCase());
    }

    private String suggestEntityClassName(String lower) {
        if (projectModel != null && (
                lower.contains("border")
                        || lower.contains("neighbour")
                        || lower.contains("neighbor"))) {
            return GeneratedQuizableSourceGenerator.sanitizeClassName(
                    projectModel.rootClass().className());
        }
        if (field != null && !field.name().isBlank()) {
            return GeneratedQuizableSourceGenerator.sanitizeClassName(singularOf(field.name()));
        }
        return "";
    }

    private static String singularOf(String s) {
        if (s == null || s.isBlank()) {
            return s;
        }
        s = s.trim();
        if (s.endsWith("ies") && s.length() > 3) {
            return s.substring(0, s.length() - 3) + "y";
        }
        if (s.endsWith("s") && s.length() > 1) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private void refreshObjectTypeBox(String selectedClass) {
        updatingObjectTypeBox = true;
        try {
        objectTypeBox.removeAllItems();
        if (projectModel != null) {
            for (GeneratedClassModel cls : projectModel.classes()) {
                objectTypeBox.addItem(cls.className());
            }
        }
        objectTypeBox.addItem(NEW_CLASS_SENTINEL);
        if (selectedClass != null && !selectedClass.isBlank()
                && !NEW_CLASS_SENTINEL.equals(selectedClass)) {
            // Add if not already in the list (e.g., a class not yet in registry)
            boolean found = false;
            for (int i = 0; i < objectTypeBox.getItemCount(); i++) {
                if (selectedClass.equals(objectTypeBox.getItemAt(i))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                objectTypeBox.insertItemAt(selectedClass, objectTypeBox.getItemCount() - 1);
            }
            objectTypeBox.setSelectedItem(selectedClass);
        }
        } finally {
            updatingObjectTypeBox = false;
        }
    }

    private String selectedEntityClass() {
        Object sel = objectTypeBox.getSelectedItem();
        if (sel == null || NEW_CLASS_SENTINEL.equals(sel)) {
            return "";
        }
        return (String) sel;
    }

    private void clear() {
        titleLabel.setText("Field");
        fieldNameField.setText("");
        refreshObjectTypeBox("");
        propertyPidField.setText("");
        propertyLabel.setText("(not selected)");
        renderModeBox.setSelectedItem(FieldRenderMode.AUTO);
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