package domain.transform.pipeline.ui;

import domain.DomainField;
import domain.curation.ScopeFilter;
import domain.transform.FilterCondition;
import domain.transform.ui.TransformController;
import domain.transform.ui.WorkbenchPurpose;
import objectview.Viewable;
import objectview.utils.swing.WrapLayout;

import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class ViewStepsPanel extends JPanel {

    public interface Listener {
        void viewStepsChanged();
    }

    private final Listener listener;
    // Selecting a field and filtering shown instances are independent.
    private final BiConsumer<DomainField, ScopeFilter> onSelectionChanged;
    private final Supplier<? extends Collection<? extends Viewable>> workingSet;
    private final TransformFieldPicker fields;
    private final FilterGroupEditor filter;
    private final JComboBox<String> selectionCombo = new JComboBox<>();
    private final JComboBox<String> secondSelectionCombo = new JComboBox<>();
    private boolean refreshingSchema;

    private final JToggleButton allScope = new JToggleButton("All");
    private final JToggleButton missingScope = new JToggleButton("Missing");
    private final JToggleButton presentScope = new JToggleButton("Present");
    private ScopeFilter scopeFilter = ScopeFilter.ALL;

    public ViewStepsPanel(
            TransformController controller,
            Listener listener,
            BiConsumer<String, FilterCondition> filterGroupCreator,
            Supplier<? extends Collection<? extends Viewable>> workingSet,
            BiConsumer<DomainField, ScopeFilter> onSelectionChanged,
            Runnable onNewField) {
        this(controller, listener, filterGroupCreator, workingSet,
                onSelectionChanged, onNewField, WorkbenchPurpose.TRANSFORM);
    }

    public ViewStepsPanel(
            TransformController controller,
            Listener listener,
            BiConsumer<String, FilterCondition> filterGroupCreator,
            Supplier<? extends Collection<? extends Viewable>> workingSet,
            BiConsumer<DomainField, ScopeFilter> onSelectionChanged,
            Runnable onNewField,
            WorkbenchPurpose purpose) {
        this.listener = listener;
        this.workingSet = workingSet == null ? List::of : workingSet;
        this.onSelectionChanged = onSelectionChanged == null ? (field, scope) -> { } : onSelectionChanged;
        boolean transformActions = purpose == null || purpose.showsTransformActions();
        this.fields = new TransformFieldPicker(controller, this.workingSet);
        this.filter = transformActions
                ? new FilterGroupEditor(controller, filterGroupCreator) : null;

        setLayout(new BorderLayout(6, 6));
        selectionCombo.addItem("All");
        secondSelectionCombo.addItem("None");
        controller.domain().selectionNames().forEach(selectionCombo::addItem);
        controller.domain().selectionNames().forEach(secondSelectionCombo::addItem);
        selectionCombo.setToolTipText(
                "Restrict the selected class to a named semantic selection");
        selectionCombo.addActionListener(e -> fireChanged());
        secondSelectionCombo.addActionListener(e -> fireChanged());
        DefaultListCellRenderer selectionRenderer = new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value != null) setText(selectionDisplayName(value.toString()));
                return this;
            }
        };
        selectionCombo.setRenderer(selectionRenderer);
        secondSelectionCombo.setRenderer(selectionRenderer);
        add(memberRow(onNewField), BorderLayout.NORTH);
        if (filter != null) {
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, fieldBlock(), filter);
            split.setResizeWeight(0.45);
            add(split, BorderLayout.CENTER);
        } else {
            add(fieldBlock(), BorderLayout.CENTER);
        }

        ButtonGroup scopeButtons = new ButtonGroup();
        scopeButtons.add(allScope);
        scopeButtons.add(missingScope);
        scopeButtons.add(presentScope);
        allScope.setSelected(true);
        allScope.addActionListener(e -> selectScope(ScopeFilter.ALL));
        missingScope.addActionListener(e -> selectScope(ScopeFilter.MISSING));
        presentScope.addActionListener(e -> selectScope(ScopeFilter.PRESENT));

        fields.onChange(this::onFieldSelectionChanged, this::fireChanged);
    }

    /** Refresh after TransformApp creates a new semantic subclass. */
    public void refreshTypes(String selectedType) {
        fields.refreshTypes(selectedType);
    }

    /** Rebuild fields when the selected group's explicit type specification changes. */
    public void refreshSchema() {
        refreshingSchema = true;
        try {
            fields.refreshFields();
        } finally {
            refreshingSchema = false;
        }
    }

    private JComponent memberRow(Runnable onNewField) {
        JPanel p = fields.classRow();
        if (selectionCombo.getItemCount() > 1) {
            p.add(new JLabel("Selection:"));
            p.add(selectionCombo);
            p.add(new JLabel("∩"));
            p.add(secondSelectionCombo);
        }
        if (filter != null) {
            // "New field…" sits with the class it declares a field on.
            JButton newField = new JButton("New field…");
            newField.setName("transform.newField");
            newField.addActionListener(e -> { if (onNewField != null) onNewField.run(); });
            p.add(newField);
        }
        return p;
    }

    public String selectedSelection() {
        Object selected = selectionCombo.getSelectedItem();
        return selected == null || "All".equals(selected) ? null : selected.toString();
    }

    public String secondSelectedSelection() {
        Object selected = secondSelectionCombo.getSelectedItem();
        return selected == null || "None".equals(selected) ? null : selected.toString();
    }

    public static String selectionDisplayName(String key) {
        if (key == null) return "";
        int suffix = key.lastIndexOf(" [");
        return suffix < 0 ? key : key.substring(0, suffix);
    }

    private JComponent fieldBlock() {
        JPanel p = fields.fieldBlock();
        JPanel scopes = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 3));
        scopes.add(new JLabel("Show:"));
        scopes.add(allScope);
        scopes.add(missingScope);
        scopes.add(presentScope);
        p.add(scopes, BorderLayout.SOUTH);
        return p;
    }

    /** Rebuild the field tree from the current schema — e.g. after a field is declared. */
    public void refreshFields() {
        fields.refreshFields();
    }

    /** Re-offer only the operators that fit the checked field's shape, and repopulate
     *  the value picker with that field's candidate values. */
    private void onFieldSelectionChanged() {
        DomainField f = fields.currentField();
        if (filter != null) filter.edit(f);
        updateScopeLabels();
        // Rebuilding rows for a newly selected group's explicit TypeSpec is a schema
        // refresh, not a user field/scope edit. Calling the parent here re-enters the
        // group's show handler, which refreshes the schema again and prevents the
        // instance/group split from ever being installed.
        if (refreshingSchema) return;
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
        DomainField field = fields.currentField();
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
        fields.refreshWorkingSet();
        updateScopeLabels();
    }

    private void updateScopeLabels() {
        Collection<? extends Viewable> members = workingSet.get();
        int all = members == null ? 0 : members.size();
        var coverage = fields.coverage();
        allScope.setText("All " + all);
        missingScope.setText("Missing " + (coverage == null ? "—" : coverage.missing()));
        presentScope.setText("Present " + (coverage == null ? "—" : coverage.present()));
        missingScope.setEnabled(coverage != null && coverage.eligible() > 0);
        presentScope.setEnabled(coverage != null && coverage.eligible() > 0);
    }

    public DomainField selectedDomainField() {
        return fields.currentField();
    }

    /** Select a newly-declared field and an explicit initial value scope in the one main
     *  selection surface. The caller may then open Curate to configure an action. */
    public void selectField(String path, ScopeFilter filter) {
        // Avoid briefly applying the previous field's mode to the new field while the
        // programmatic row selection fires its normal change notification.
        scopeFilter = ScopeFilter.ALL;
        allScope.setSelected(true);
        fields.selectField(path);
        selectScope(filter);
    }

    private void fireChanged() {
        if (listener != null) {
            listener.viewStepsChanged();
        }
    }
}
