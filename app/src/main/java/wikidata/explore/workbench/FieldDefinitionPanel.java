package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldDefinition;
import wikidata.explore.model.FieldRenderMode;
import wikidata.explore.model.FieldType;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;

/** Shared editor for the source-independent part of a field definition. */
public final class FieldDefinitionPanel extends JPanel {

    /** The placeholder shown when no class is chosen. It is a prompt, never a class
     *  name: read back as one it became the field's target, and the model then held a
     *  reference to a class called "(none)". */
    public static final String NO_CLASS = "(none)";

    private final JTextField name = new JTextField(16);
    private final JComboBox<FieldType> type = new JComboBox<>(FieldType.values());
    private final JComboBox<String> targetType = new JComboBox<>();
    private final JComboBox<FieldCardinality> cardinality =
            new JComboBox<>(FieldCardinality.values());
    private final JComboBox<FieldRenderMode> renderMode =
            new JComboBox<>(FieldRenderMode.values());
    // "An entity, unclassed": the values keep their QID and label, and no class is
    // named for them. Without it, the only way to hold item-valued answers (P734's
    // family-name item) was to invent an empty class per property.
    private final JCheckBox unclassedEntity = new JCheckBox("no class (keep label)");

    public FieldDefinitionPanel() {
        super(new GridBagLayout());
        setBorder(BorderFactory.createTitledBorder("Field definition"));
        targetType.setEditable(true);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        GridBagUtils.labeledRow(this, c, row++, "Field name:", name);
        GridBagUtils.labeledRow(this, c, row++, "Holds:", type);
        GridBagUtils.labeledRow(this, c, row++, "Of class:", targetType);
        GridBagUtils.labeledRow(this, c, row++, "", unclassedEntity);
        GridBagUtils.labeledRow(this, c, row++, "Count:", cardinality);
        GridBagUtils.labeledRow(this, c, row, "Display:", renderMode);

        type.setToolTipText("The scalar/media kind, or Entity for a domain reference.");
        targetType.setToolTipText("Logical target class when Holds = Entity.");
        cardinality.setToolTipText("Single value or collection; Auto is resolved by the producer.");
        renderMode.setToolTipText("Auto, inline value/object, or reference rendering.");
        unclassedEntity.setToolTipText("The values are entities — identity and label "
                + "kept — but this model names no class for them.");
        type.addActionListener(e -> refreshEnabled());
        unclassedEntity.addActionListener(e -> refreshEnabled());
        refreshEnabled();
    }

    public void availableTargetTypes(Collection<String> types) {
        Object selected = targetType.getEditor().getItem();
        targetType.removeAllItems();
        if (types != null) {
            for (String value : types) {
                if (value != null && !value.isBlank()) targetType.addItem(value);
            }
        }
        if (selected != null) targetType.setSelectedItem(selected);
    }

    public void edit(FieldDefinition definition) {
        FieldDefinition value = definition == null
                ? new FieldDefinition("", FieldType.AUTO, "",
                        FieldCardinality.AUTO, FieldRenderMode.AUTO)
                : definition;
        name.setText(value.name());
        type.setSelectedItem(value.type());
        targetType.setSelectedItem(value.entityClassName());
        cardinality.setSelectedItem(value.cardinality());
        renderMode.setSelectedItem(value.renderMode());
        unclassedEntity.setSelected(value.unclassedEntity());
        refreshEnabled();
    }

    public FieldDefinition definition() {
        FieldType selectedType = (FieldType) type.getSelectedItem();
        Object target = targetType.getEditor().getItem();
        // Only an ENTITY field has a referenced class. The target combo may be shared
        // (with a "(none)" sentinel) or hold a stale value, so blank it otherwise —
        // never leak the sentinel into a scalar field's entityClassName.
        String chosen = target == null ? "" : target.toString().trim();
        if (NO_CLASS.equals(chosen)) {
            chosen = "";
        }
        String entityClass = selectedType == FieldType.ENTITY ? chosen : "";
        boolean unclassed = selectedType == FieldType.ENTITY
                && unclassedEntity.isSelected();
        return new FieldDefinition(name.getText(), selectedType,
                unclassed ? "" : entityClass,
                (FieldCardinality) cardinality.getSelectedItem(),
                (FieldRenderMode) renderMode.getSelectedItem(),
                unclassed);
    }

    public String validationError() {
        FieldDefinition value = definition();
        if (value.name().isBlank()) return "Field name is required.";
        if (wikidata.explore.model.GeneratedClassModel
                .isReservedFieldName(value.name())) {
            return "'" + value.name().trim()
                    + "' already exists as a built-in identity/display field.";
        }
        if (value.type() == FieldType.AUTO) return "Choose what the field holds.";
        if (value.cardinality() == FieldCardinality.AUTO) return "Choose Single or List.";
        if (value.type() == FieldType.ENTITY && value.entityClassName().isBlank()
                && !value.unclassedEntity()) {
            return "Choose the referenced class, or tick 'no class (keep label)'.";
        }
        return null;
    }

    JTextField nameField() { return name; }
    JComboBox<FieldType> typeBox() { return type; }
    JComboBox<String> targetTypeBox() { return targetType; }
    JComboBox<FieldCardinality> cardinalityBox() { return cardinality; }
    JComboBox<FieldRenderMode> renderModeBox() { return renderMode; }

    private void refreshEnabled() {
        boolean entity = type.getSelectedItem() == FieldType.ENTITY;
        unclassedEntity.setEnabled(entity);
        targetType.setEnabled(entity && !unclassedEntity.isSelected());
    }
}
