package quiz.transform.pipeline.ui;

import objectview.Viewable;
import objectview.viewconfig.FieldRow;
import objectview.viewconfig.FieldTableContributor;
import objectview.viewconfig.ViewConfig;
import objectview.viewconfig.ViewConfigEditor;
import quiz.transform.ui.DomainField;
import objectview.field.FieldKind;
import quiz.transform.ui.TransformController;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ViewStepsPanel extends JPanel {

    public interface Listener {
        void viewStepsChanged();
    }

    private final TransformController controller;
    private final Listener listener;
    private final java.util.function.BiConsumer<String, FilterCondition> filterGroupCreator;

    private final JComboBox<String> memberTypeCombo = new JComboBox<>();
    private boolean refreshingTypes;

    // The single-select field picker: the shared field-config table in SINGLE mode over
    // the SHARED config source, so it shows the same fields / order / types as the
    // search/sort/view editors, with references as an inline collapsible tree. The
    // chosen DomainField is rebuilt from the selected row's FieldRow.
    private final ViewConfigEditor fieldPicker =
            new ViewConfigEditor(new ViewConfig(), (Viewable) null, FieldTableContributor.SINGLE);

    private final JComboBox<FilterOperator> filterOperator =
            new JComboBox<>();
    // Editable so it doubles as a free-text box; its dropdown is repopulated per
    // selected field with that field's candidate values (enum constants / distinct
    // low-cardinality categorical values), empty for free-text fields.
    private final JComboBox<String> filterValue = editableCombo();
    private final JTextField filterValue2 = new JTextField(10);

    private static JComboBox<String> editableCombo() {
        JComboBox<String> c = new JComboBox<>();
        c.setEditable(true);
        c.setPrototypeDisplayValue("wwwwwwww");
        return c;
    }

    private static String comboText(JComboBox<String> combo) {
        Object item = combo.isEditable()
                ? combo.getEditor().getItem()
                : combo.getSelectedItem();
        return item == null ? "" : item.toString();
    }

    public ViewStepsPanel(
            TransformController controller,
            Listener listener,
            java.util.function.BiConsumer<String, FilterCondition> filterGroupCreator) {
        this.controller = controller;
        this.listener = listener;
        this.filterGroupCreator = filterGroupCreator;

        setLayout(new BorderLayout(6, 6));

        for (String t : controller.types()) {
            memberTypeCombo.addItem(t);
        }
        // Show the instance count beside each type (display only — the item value stays
        // the plain type name, which selectType and the pipeline rely on).
        memberTypeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof String t) {
                    setText(t + "  (" + controller.instanceCount(t) + ")");
                }
                return this;
            }
        });

        memberTypeCombo.addActionListener(e -> {
            if (refreshingTypes) return;
            String chosen = (String) memberTypeCombo.getSelectedItem();
            // A JComboBox fires on re-selecting the value that's already showing;
            // only a genuine type change should reset the steps (its groups/filters
            // reference the old class's fields). Re-picking the same class keeps them.
            if (java.util.Objects.equals(chosen, controller.selectedType())) {
                return;
            }
            controller.selectType(chosen);
            rebuildFieldTree();
            fireChanged();
        });

        add(memberRow(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                fieldBlock(),
                stepsBlock()
        );
        split.setResizeWeight(0.45);

        add(split, BorderLayout.CENTER);

        reloadOperators(FieldKind.UNKNOWN);
        filterOperator.addActionListener(e -> updateFilterValueEnablement());

        // Every class opens on its root group (all instances, no steps) — build
        // groups/filters yourself. Select the first member type to seed the field tree.
        if (memberTypeCombo.getItemCount() > 0) {
            controller.selectType((String) memberTypeCombo.getSelectedItem());
            rebuildFieldTree();
        }
    }

    /** Refresh after TransformApp creates a new semantic subclass. */
    public void refreshTypes(String selectedType) {
        refreshingTypes = true;
        try {
            memberTypeCombo.removeAllItems();
            for (String type : controller.types()) memberTypeCombo.addItem(type);
            if (selectedType != null) memberTypeCombo.setSelectedItem(selectedType);
        } finally {
            refreshingTypes = false;
        }
        controller.selectType((String) memberTypeCombo.getSelectedItem());
        rebuildFieldTree();
    }

    private JComponent memberRow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.add(new JLabel("Members:"));
        p.add(memberTypeCombo);
        return p;
    }

    private JComponent fieldBlock() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createTitledBorder(
                "Field — pick one (a reference's nested fields are indented below it)"));
        fieldPicker.setChangeListener(this::onFieldSelectionChanged);
        p.add(fieldPicker, BorderLayout.CENTER);
        return p;
    }

    private JComponent stepsBlock() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(filterPanel(), BorderLayout.CENTER);
        return p;
    }

    private JComponent filterPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        row.add(new JLabel("Operator:"));
        row.add(filterOperator);
        row.add(new JLabel("Value:"));
        row.add(filterValue);
        row.add(new JLabel("and:"));
        row.add(filterValue2);

        p.add(row, BorderLayout.NORTH);
        return p;
    }

    /** Rebuild the field tree from the current schema — e.g. after a field is declared. */
    public void refreshFields() {
        rebuildFieldTree();
    }

    private void rebuildFieldTree() {
        String type = controller.selectedType();
        if (type == null) {
            fieldPicker.setConfigRows(new ViewConfig(), null, null, java.util.Set.of());
        } else {
            Viewable sample = controller.sampleOf(type);
            fieldPicker.setConfigRows(
                    sample == null ? new ViewConfig() : ViewConfig.all(sampleClass(sample)),
                    sample,
                    controller.fieldTypes(type),
                    controller.structuralFields(type));
        }
        onFieldSelectionChanged();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Viewable> sampleClass(Viewable q) {
        return (Class<? extends Viewable>) q.getClass();
    }

    private DomainField singleCheckedField() {
        DomainField f = currentField();
        if (f == null) {
            JOptionPane.showMessageDialog(this, "Select a field first.");
        }
        return f;
    }

    /** The selected field as a {@link DomainField}. Prefer the domain's authoritative
     *  shape: a dynamic snapshot row has no reflected {@link Field}, so reconstructing
     *  it from the row alone misclassifies e.g. {@code Collection<MediaValue>} as one
     *  MEDIA value and loses the collection-size operators. */
    private DomainField currentField() {
        return domainFieldForRow(
                controller, controller.selectedType(), fieldPicker.selectedRow());
    }

    static DomainField domainFieldForRow(
            TransformController controller, String type, FieldRow row) {
        if (row == null) {
            return null;
        }

        if (controller != null && type != null) {
            for (DomainField field : controller.fields(type)) {
                if (field.field().equals(row.path())) {
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
        return new DomainField(type, row.path(), reference, collection, kind);
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

    /** Re-offer only the operators that fit the checked field's shape, and repopulate
     *  the value picker with that field's candidate values. */
    private void onFieldSelectionChanged() {
        DomainField f = currentField();
        reloadOperators(kindOf(f));
        populateValueChoices(f);
    }

    /** Repopulate the value combo's dropdown with the field's candidate values (enum /
     *  categorical), leaving it editable for free-text fields. Clears the editor so the
     *  user picks fresh for the newly-selected field. */
    private void populateValueChoices(DomainField f) {
        List<String> choices = f == null
                ? List.of()
                : controller.candidateValues(controller.selectedType(), f.field());
        filterValue.setModel(new DefaultComboBoxModel<>(choices.toArray(new String[0])));
        filterValue.setSelectedItem("");
    }

    /** The value shape of a field: the domain-populated {@link DomainField#kind()}
     *  if known, else inferred from a representative value (cached by the controller
     *  — the first instance's value may be null while the field is numeric). */
    private FieldKind kindOf(DomainField f) {
        if (f == null) {
            return FieldKind.UNKNOWN;
        }
        if (f.collection()) {
            return FieldKind.COLLECTION;
        }
        if (f.reference()) {
            return FieldKind.REFERENCE;
        }
        if (f.kind() != null && f.kind() != FieldKind.UNKNOWN) {
            return f.kind();
        }
        return FieldKind.ofValue(
                controller.sampleFieldValue(controller.selectedType(), f.field()));
    }

    /** Repopulate the operator combo with only the operators applicable to {@code
     *  kind}, keeping the current selection if it still fits. */
    private void reloadOperators(FieldKind kind) {
        FilterOperator previous = (FilterOperator) filterOperator.getSelectedItem();
        filterOperator.removeAllItems();
        FilterOperator keep = null;
        for (FilterOperator op : FilterOperator.values()) {
            if (op.appliesTo(kind)) {
                filterOperator.addItem(op);
                if (op == previous) {
                    keep = op;
                }
            }
        }
        if (keep != null) {
            filterOperator.setSelectedItem(keep);
        }
        updateFilterValueEnablement();
    }

    /** Enable only the value field(s) the selected operator uses: none for a unary
     *  predicate (is true / is empty …), both for BETWEEN, else just the first. */
    private void updateFilterValueEnablement() {
        FilterOperator op = (FilterOperator) filterOperator.getSelectedItem();
        filterValue.setEnabled(op != null && !op.isUnary());
        filterValue2.setEnabled(op != null && op.isBinary());
    }

    /** Create a named filter group from the rule currently shown in this editor. */
    public void requestAddFilterGroup() {
        DomainField f = singleCheckedField();
        if (f == null) {
            return;
        }

        FilterOperator op = (FilterOperator) filterOperator.getSelectedItem();
        if (op == null) {
            return;
        }

        Object v1 = parseValue(comboText(filterValue));
        Object v2 = parseValue(filterValue2.getText());

        FilterCondition c = new FilterCondition(f, op, v1, v2);
        String name = JOptionPane.showInputDialog(this,
                "Name the new filter group:", "Filtered " + f.path());
        if (name == null || name.isBlank()) return;
        if (filterGroupCreator != null) {
            filterGroupCreator.accept(name.trim(), c);
        }
    }

    public DomainField selectedDomainField() {
        return currentField();
    }

    public void reflectGroupRule(objectview.group.ViewableGroup<?> group) {
        if (group instanceof quiz.transform.FacetGroup facet) {
            fieldPicker.setSelectedPath(facet.field());
        } else if (group instanceof quiz.transform.OperationGroup operation) {
            FilterCondition c = operation.condition();
            if (c.field() != null) fieldPicker.setSelectedPath(c.field().field());
            filterOperator.setSelectedItem(c.operator());
            filterValue.setSelectedItem(c.value() == null ? "" : String.valueOf(c.value()));
            filterValue2.setText(c.value2() == null ? "" : String.valueOf(c.value2()));
        }
    }

    private static Object parseValue(String text) {
        return TransformController.parseValue(text);
    }

    private void fireChanged() {
        if (listener != null) {
            listener.viewStepsChanged();
        }
    }
}
