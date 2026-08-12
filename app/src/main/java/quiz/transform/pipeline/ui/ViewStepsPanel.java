package quiz.transform.pipeline.ui;

import objectview.Viewable;
import objectview.field.FieldPath;
import objectview.viewconfig.FieldRow;
import objectview.viewconfig.FieldTableContributor;
import objectview.viewconfig.ViewConfig;
import objectview.viewconfig.ViewConfigEditor;
import quiz.transform.ui.DomainField;
import objectview.field.FieldKind;
import quiz.curation.ScopeFilter;
import quiz.transform.ui.FieldCoverageColumns;
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
    // Field selection and value scope are deliberately independent. Selecting a row says
    // which field subsequent actions refer to; only these explicit All/Missing/Present
    // controls change the instances shown on the right.
    private final java.util.function.BiConsumer<DomainField, ScopeFilter> onSelectionChanged;
    private final java.util.function.Supplier<
            ? extends java.util.Collection<? extends Viewable>> workingSet;
    // Declaring a new field acts on the selected class, so its button sits by the Class row.
    private final Runnable onNewField;

    private final JComboBox<String> memberTypeCombo = new JComboBox<>();
    private boolean refreshingTypes;

    // The single-select field picker: the shared field-config table in SINGLE mode over
    // the SHARED config source, so it shows the same fields / order / types as the
    // search/sort/view editors, with references as an inline collapsible tree. The
    // chosen DomainField is rebuilt from the selected row's FieldRow.
    private final ViewConfigEditor fieldPicker;
    private final FieldCoverageColumns coverageColumns;
    private final JToggleButton allScope = new JToggleButton("All");
    private final JToggleButton missingScope = new JToggleButton("Missing");
    private final JToggleButton presentScope = new JToggleButton("Present");
    private ScopeFilter scopeFilter = ScopeFilter.ALL;

    private final JComboBox<FilterOperator> filterOperator =
            new JComboBox<>();
    // Editable so it doubles as a free-text box; its dropdown is repopulated per
    // selected field with that field's candidate values (enum constants / distinct
    // low-cardinality categorical values), empty for free-text fields.
    private final JComboBox<String> filterValue = editableCombo();
    private final JTextField filterValue2 = new JTextField(10);
    private final JButton addFilterGroup = new JButton("Add filter group");

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
            java.util.function.BiConsumer<String, FilterCondition> filterGroupCreator,
            java.util.function.Supplier<
                    ? extends java.util.Collection<? extends Viewable>> workingSet,
            java.util.function.BiConsumer<DomainField, ScopeFilter> onSelectionChanged,
            Runnable onNewField) {
        this.controller = controller;
        this.listener = listener;
        this.filterGroupCreator = filterGroupCreator;
        this.workingSet = workingSet == null ? List::of : workingSet;
        this.onSelectionChanged = onSelectionChanged == null
                ? (f, s) -> { } : onSelectionChanged;
        this.onNewField = onNewField == null ? () -> { } : onNewField;
        // The same coverage contributor is used here and in curation. It describes the
        // current group; it does not decide which part of that group is visible.
        this.coverageColumns = new FieldCoverageColumns(
                controller.domain(), controller::selectedType, this.workingSet);
        this.fieldPicker = new ViewConfigEditor(new ViewConfig(), (Viewable) null,
                coverageColumns);

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
        addFilterGroup.setToolTipText(
                "Make the instances matching this condition a named group");
        addFilterGroup.addActionListener(e -> requestAddFilterGroup());
        ButtonGroup scopeButtons = new ButtonGroup();
        scopeButtons.add(allScope);
        scopeButtons.add(missingScope);
        scopeButtons.add(presentScope);
        allScope.setSelected(true);
        allScope.addActionListener(e -> selectScope(ScopeFilter.ALL));
        missingScope.addActionListener(e -> selectScope(ScopeFilter.MISSING));
        presentScope.addActionListener(e -> selectScope(ScopeFilter.PRESENT));

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
        p.add(new JLabel("Class:"));
        p.add(memberTypeCombo);
        // "New field…" sits with the class it declares a field on.
        JButton newField = new JButton("New field…");
        newField.addActionListener(e -> onNewField.run());
        p.add(newField);
        return p;
    }

    private JComponent fieldBlock() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createTitledBorder(
                "Field — pick one (a reference's nested fields are indented below it)"));
        fieldPicker.setChangeListener(this::onFieldSelectionChanged);
        p.add(fieldPicker, BorderLayout.CENTER);
        JPanel scopes = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        scopes.add(new JLabel("Show:"));
        scopes.add(allScope);
        scopes.add(missingScope);
        scopes.add(presentScope);
        p.add(scopes, BorderLayout.SOUTH);
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
        // The control that commits the rule sits with the rule. It used to live only in the
        // group-tree bar, so nothing beside the operator said where the condition was going.
        row.add(addFilterGroup);

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
            Viewable sample = controller.configSample(type);
            fieldPicker.setConfigRows(
                    sample == null ? new ViewConfig() : ViewConfig.all(sampleClass(sample)),
                    sample,
                    controller.fieldTypes(type),
                    controller.structuralFields(type));
            // Subclass-aware, like the coverage picker: show a subtype's OWN fields
            // (e.g. USState.admissionDate) under its heading, so Filter can target them.
            fieldPicker.setClassBranches(
                    quiz.transform.ui.DomainSchemas.classBranches(controller.domain(), type));
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

    /** Re-offer only the operators that fit the checked field's shape, and repopulate
     *  the value picker with that field's candidate values. */
    private void onFieldSelectionChanged() {
        DomainField f = currentField();
        reloadOperators(kindOf(f));
        populateValueChoices(f);
        updateScopeLabels();
        // Field selection and value scope are separate pieces of state, but the parent needs
        // both on every change: field actions still target the selected field while All is
        // active. Missing/Present cannot survive clearing the field selection.
        if (f == null && scopeFilter != ScopeFilter.ALL) {
            selectScope(ScopeFilter.ALL);
        } else {
            onSelectionChanged.accept(f, scopeFilter);
        }
    }

    private void selectScope(ScopeFilter selected) {
        DomainField field = currentField();
        scopeFilter = selected == null ? ScopeFilter.ALL : selected;
        if (scopeFilter != ScopeFilter.ALL && field == null) {
            scopeFilter = ScopeFilter.ALL;
        }
        allScope.setSelected(scopeFilter == ScopeFilter.ALL);
        missingScope.setSelected(scopeFilter == ScopeFilter.MISSING);
        presentScope.setSelected(scopeFilter == ScopeFilter.PRESENT);
        updateScopeLabels();
        onSelectionChanged.accept(field, scopeFilter);
    }

    /** Recompute coverage after a group switch or in-place curation and refresh the labels
     *  without changing either the selected field or the explicit value scope. */
    public void refreshWorkingSet() {
        coverageColumns.invalidate();
        updateScopeLabels();
        fieldPicker.repaint();
    }

    private void updateScopeLabels() {
        java.util.Collection<? extends Viewable> members = workingSet.get();
        int all = members == null ? 0 : members.size();
        FieldRow row = fieldPicker.selectedRow();
        FieldCoverageColumns.Coverage coverage = row == null
                ? null : coverageColumns.coverage(row.path());
        allScope.setText("All " + all);
        missingScope.setText("Missing " + (coverage == null ? "—" : coverage.missing()));
        presentScope.setText("Present " + (coverage == null ? "—" : coverage.present()));
        missingScope.setEnabled(coverage != null && coverage.eligible() > 0);
        presentScope.setEnabled(coverage != null && coverage.eligible() > 0);
    }

    /** Repopulate the value combo's dropdown with the field's candidate values (enum /
     *  categorical), leaving it editable for free-text fields. Clears the editor so the
     *  user picks fresh for the newly-selected field. */
    private void populateValueChoices(DomainField f) {
        List<String> choices = f == null
                ? List.of()
                : controller.candidateValues(f.type(), f.fieldPath());
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
                controller.sampleFieldValue(f.type(), f.fieldPath()));
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
        // A condition needs both halves — no field selected means there is nothing to
        // filter ON, whatever the operator says.
        addFilterGroup.setEnabled(op != null && currentField() != null);
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
                "Name the new filter group:", "Filtered " + f.displayPath());
        if (name == null || name.isBlank()) return;
        if (filterGroupCreator != null) {
            filterGroupCreator.accept(name.trim(), c);
        }
    }

    public DomainField selectedDomainField() {
        return currentField();
    }

    /** Select a newly-declared field and an explicit initial value scope in the one main
     *  selection surface. The caller may then open Curate to configure an action. */
    public void selectField(String path, ScopeFilter filter) {
        // Avoid briefly applying the previous field's mode to the new field while the
        // programmatic row selection fires its normal change notification.
        scopeFilter = ScopeFilter.ALL;
        allScope.setSelected(true);
        fieldPicker.setSelectedPath(FieldPath.parse(path));
        selectScope(filter);
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
