package domain.transform.pipeline.ui;

import domain.DomainField;
import domain.DomainSchemas;
import domain.transform.ui.FieldCoverageColumns;
import domain.transform.ui.TransformController;
import objectview.Viewable;
import objectview.field.FieldKind;
import objectview.field.FieldPath;
import objectview.utils.swing.WrapLayout;
import objectview.viewconfig.FieldRow;
import objectview.viewconfig.ViewConfig;
import objectview.viewconfig.ViewConfigEditor;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** The class and single-field selection shared by field actions and filter creation. */
final class TransformFieldPicker {
    private final TransformController controller;
    private final JComboBox<String> memberTypeCombo = new JComboBox<>();
    private final ViewConfigEditor fieldPicker;
    private final FieldCoverageColumns coverageColumns;
    private boolean refreshingTypes;
    private Runnable fieldChanged = () -> {};
    private boolean refreshingFields;

    TransformFieldPicker(TransformController controller,
            Supplier<? extends Collection<? extends Viewable>> workingSet) {
        this.controller = controller;
        coverageColumns = new FieldCoverageColumns(
                controller.domain(), controller::selectedType, workingSet);
        fieldPicker = new ViewConfigEditor(new ViewConfig(), (Viewable) null, coverageColumns);
        controller.types().forEach(memberTypeCombo::addItem);
        memberTypeCombo.setName("view.class");
        controller.selectType((String) memberTypeCombo.getSelectedItem());
    }

    void onChange(Runnable fieldChanged, Runnable classChanged) {
        this.fieldChanged = fieldChanged;
        fieldPicker.setChangeListener(() -> { if (!refreshingFields) fieldChanged.run(); });
        memberTypeCombo.addActionListener(e -> {
            if (refreshingTypes) return;
            String chosen = (String) memberTypeCombo.getSelectedItem();
            // Reselecting the current class must not reset its groups.
            if (Objects.equals(chosen, controller.selectedType())) return;
            controller.selectType(chosen);
            refreshFields();
            classChanged.run();
        });
        refreshFields();
    }

    JPanel classRow() {
        JPanel row = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        JLabel label = new JLabel("Class:");
        label.setLabelFor(memberTypeCombo);
        row.add(label);
        row.add(memberTypeCombo);
        return row;
    }

    JPanel fieldBlock() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder(
                "Field — pick one (a reference's nested fields are indented below it)"));
        panel.add(fieldPicker, BorderLayout.CENTER);
        return panel;
    }

    void classSelectionEnabled(boolean enabled) { memberTypeCombo.setEnabled(enabled); }

    void selectField(String path) { fieldPicker.setSelectedPath(FieldPath.parse(path)); }

    FieldCoverageColumns.Coverage coverage() {
        FieldRow row = fieldPicker.selectedRow();
        return row == null ? null : coverageColumns.coverage(row.path());
    }

    void refreshWorkingSet() {
        coverageColumns.invalidate();
        fieldPicker.repaint();
    }

    /** Refresh after TransformApp creates a new semantic subclass. */
    void refreshTypes(String selectedType) {
        refreshingTypes = true;
        try {
            memberTypeCombo.removeAllItems();
            for (String type : controller.types()) memberTypeCombo.addItem(type);
            if (selectedType != null) memberTypeCombo.setSelectedItem(selectedType);
        } finally {
            refreshingTypes = false;
        }
        controller.selectType((String) memberTypeCombo.getSelectedItem());
        refreshFields();
    }

    void refreshFields() {
        refreshingFields = true;
        try {
            String type = controller.selectedType();
            if (type == null) {
                fieldPicker.setConfigRows(new ViewConfig(), null, null, Set.of());
            } else {
                Viewable sample = controller.configSample(type);
                fieldPicker.setConfigRows(
                        sample == null ? new ViewConfig() : ViewConfig.of(sampleClass(sample)),
                        sample,
                        controller.fieldTypes(type),
                        controller.structuralFields(type));
                // Subclass-aware, like the coverage picker: show a subtype's OWN fields
                // (e.g. USState.admissionDate) under its heading, so Filter can target them.
                fieldPicker.setClassBranches(
                        DomainSchemas.classBranches(controller.domain(), type));
            }
        } finally {
            refreshingFields = false;
        }
        fieldChanged.run();
    }

    private static Class<? extends Viewable> sampleClass(Viewable q) {
        return q.getClass();
    }

    /** The selected field as a {@link DomainField}. Prefer the domain's authoritative
     *  shape: a dynamic snapshot row has no reflected {@link java.lang.reflect.Field}, so reconstructing
     *  it from the row alone misclassifies e.g. {@code Collection<MediaValue>} as one
     *  MEDIA value and loses the collection-size operators. */
    DomainField currentField() {
        return domainFieldForRow(
                controller, controller.selectedType(), fieldPicker.selectedRow());
    }

    static DomainField domainFieldForRow(
            TransformController controller, String type, FieldRow row) {
        if (row == null) {
            return null;
        }

        // ObjectView keeps subtype branches under a stable synthetic config key. Resolve
        // that presentation path once at the application boundary: domain operations must
        // receive the real owning class and its plain field path, never @subtype:... .
        FieldCoverageColumns.Scoped scoped = FieldCoverageColumns.scoped(type, row.path());
        String ownerType = scoped == null ? type : scoped.type();
        FieldPath fieldPath = scoped == null ? row.path() : scoped.path();

        if (controller != null && ownerType != null) {
            for (DomainField field : controller.fields(ownerType)) {
                if (field.fieldPath().equals(fieldPath)) {
                    return field;
                }
            }
        }

        // Fallback for a UI-contributed row not present in the DomainModel.
        boolean reference = row.nested() != null;
        java.lang.reflect.Field leaf = row.field();
        boolean collection = leaf != null
                && (java.util.Collection.class.isAssignableFrom(leaf.getType())
                        || java.util.Map.class.isAssignableFrom(leaf.getType()))
                || isContainerTypeLabel(row.typeLabel());
        FieldKind kind = collection ? FieldKind.COLLECTION
                : reference ? FieldKind.REFERENCE
                : leaf != null ? FieldKind.ofClass(leaf.getType())
                : FieldKind.ofTypeLabel(row.typeLabel());
        return new DomainField(ownerType, fieldPath, reference, collection, kind);
    }

    private static boolean isContainerTypeLabel(String label) {
        if (label == null) {
            return false;
        }
        String normalized = label.trim();
        return normalized.equals("Collection")
                || normalized.startsWith("Collection<")
                || normalized.equals("List")
                || normalized.startsWith("List<")
                || normalized.equals("Set")
                || normalized.startsWith("Set<")
                || normalized.equals("Map")
                || normalized.startsWith("Map<");
    }
}
