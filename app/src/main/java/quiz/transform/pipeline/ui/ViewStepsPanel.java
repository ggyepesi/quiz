package quiz.transform.pipeline.ui;

import objectview.utils.swing.GridBagUtils;
import objectview.viewconfig.FieldTableContributor;
import objectview.viewconfig.ViewConfigEditor;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.PathTypeLabel;
import objectview.field.FieldKind;
import quiz.transform.ui.OperationKind;
import quiz.transform.ui.OperationSpec;
import quiz.transform.ui.TransformController;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ViewStepsPanel extends JPanel {

    public interface Listener {
        void viewStepsChanged();
    }

    private final TransformController controller;
    private final Listener listener;

    private final JComboBox<String> memberTypeCombo = new JComboBox<>();

    // The single-select field picker (the fold-in of the old FieldTreePanel): the
    // shared field-config table run in SINGLE mode. It speaks dotted paths, so we
    // keep the path -> DomainField map to recover the chosen field.
    private final ViewConfigEditor fieldPicker =
            new ViewConfigEditor(FieldTableContributor.SINGLE);
    private final Map<String, DomainField> fieldByPath = new LinkedHashMap<>();

    private final JComboBox<FilterOperator> filterOperator =
            new JComboBox<>();
    private final JTextField filterValue = new JTextField(10);
    private final JTextField filterValue2 = new JTextField(10);

    private final DefaultListModel<FilterCondition> filterModel =
            new DefaultListModel<>();
    private final JList<FilterCondition> filterList =
            new JList<>(filterModel);

    private final DefaultMutableTreeNode groupRoot =
            new DefaultMutableTreeNode("Groups");
    private final DefaultTreeModel groupTreeModel =
            new DefaultTreeModel(groupRoot);
    private final JTree groupTree = new JTree(groupTreeModel);

    // Suppresses the member-combo listener while we set it programmatically (seeding),
    // so mirroring the widget to the controller doesn't wipe the just-seeded steps.
    private boolean syncing;

    public ViewStepsPanel(TransformController controller, Listener listener) {
        this.controller = controller;
        this.listener = listener;

        setLayout(new BorderLayout(6, 6));

        for (String t : controller.types()) {
            memberTypeCombo.addItem(t);
        }

        memberTypeCombo.addActionListener(e -> {
            if (syncing) {
                return;
            }
            controller.selectType((String) memberTypeCombo.getSelectedItem());
            rebuildFieldTree();
            clearViewSteps();
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

        // Seed a ready-to-run default (e.g. Oscar winners by category by year) and
        // mirror it into the controls, so the left panel matches the first render;
        // otherwise just select the first member type.
        if (controller.seedDefault()) {
            selectComboSilently(controller.selectedType());
            rebuildFieldTree();
            syncFromPipeline();
        } else if (memberTypeCombo.getItemCount() > 0) {
            controller.selectType((String) memberTypeCombo.getSelectedItem());
            rebuildFieldTree();
        }
    }

    /** Set the member combo without firing its listener (keeps the seeded steps). */
    private void selectComboSilently(String type) {
        syncing = true;
        memberTypeCombo.setSelectedItem(type);
        syncing = false;
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
        JPanel p = new JPanel(new GridBagLayout());
        Insets insets = new Insets(2, 2, 2, 2);
        p.add(filterPanel(), GridBagUtils.gbc(0, 0, 1.0, 0.45,
                GridBagConstraints.CENTER, GridBagConstraints.BOTH, insets));
        p.add(groupPanel(), GridBagUtils.gbc(0, 1, 1.0, 0.55,
                GridBagConstraints.CENTER, GridBagConstraints.BOTH, insets));
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

        JButton add = new JButton("Add filter");
        add.addActionListener(e -> addFilter());
        row.add(add);

        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> {
            int i = filterList.getSelectedIndex();
            if (i >= 0) {
                filterModel.remove(i);
                syncControllerPipeline();
            }
        });
        row.add(remove);

        // One condition selected at a time; selecting it mirrors its field / operator
        // / values back into the controls so it can be inspected and re-added.
        filterList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        filterList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                reflectSelectedFilter();
            }
        });

        p.add(row, BorderLayout.NORTH);
        p.add(new JScrollPane(filterList), BorderLayout.CENTER);
        return p;
    }

    private JComponent groupPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        groupTree.setRootVisible(true);
        groupTree.setShowsRootHandles(true);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));

        JButton addNested = new JButton("Add nested group");
        addNested.addActionListener(e -> addGroup(false));

        JButton addIndependent = new JButton("Add independent group");
        addIndependent.addActionListener(e -> addGroup(true));

        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> removeSelectedGroup());

        row.add(addNested);
        row.add(addIndependent);
        row.add(remove);

        groupTree.addTreeSelectionListener(e -> reflectSelectedGroup());

        p.add(row, BorderLayout.NORTH);
        p.add(new JScrollPane(groupTree), BorderLayout.CENTER);
        return p;
    }

    private void reflectSelectedGroup() {
        DefaultMutableTreeNode node = selectedGroupNode();

        if (node == null || node == groupRoot) {
            return;
        }

        Object uo = node.getUserObject();

        if (uo instanceof GroupNode g && g.field() != null) {
            fieldPicker.setSelectedPath(g.field().field());
        }
    }

    private void rebuildFieldTree() {
        String type = controller.selectedType();
        fieldByPath.clear();
        if (type == null) {
            fieldPicker.setPathRows(List.of(), java.util.Set.of(), null);
        } else {
            List<DomainField> fields = controller.fields(type);
            List<String> paths = new ArrayList<>();
            for (DomainField f : fields) {
                fieldByPath.put(f.field(), f);
                paths.add(f.field());
            }
            fieldPicker.setPathRows(paths, controller.structuralFields(type),
                    PathTypeLabel.of(fieldByPath, controller.fieldTypes(type)));
        }
        onFieldSelectionChanged();
    }

    private DomainField singleCheckedField() {
        DomainField f = currentField();
        if (f == null) {
            JOptionPane.showMessageDialog(this, "Select a field first.");
        }
        return f;
    }

    /** The selected field, or null (no dialog) — for reacting to selection. */
    private DomainField currentField() {
        return fieldByPath.get(fieldPicker.selectedPath());
    }

    /** Re-offer only the operators that fit the checked field's shape. */
    private void onFieldSelectionChanged() {
        reloadOperators(kindOf(currentField()));
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

    /** Mirror the selected filter condition back into the field check, operator, and
     *  value fields, so a condition can be inspected and re-edited. */
    private void reflectSelectedFilter() {
        int i = filterList.getSelectedIndex();
        if (i < 0) {
            return;
        }
        FilterCondition c = filterModel.get(i);
        if (c.field() != null) {
            fieldPicker.setSelectedPath(c.field().field());
        }
        reloadOperators(kindOf(c.field()));
        filterOperator.setSelectedItem(c.operator());
        filterValue.setText(c.value() == null ? "" : String.valueOf(c.value()));
        filterValue2.setText(c.value2() == null ? "" : String.valueOf(c.value2()));
        updateFilterValueEnablement();
    }

    private void addFilter() {
        DomainField f = singleCheckedField();
        if (f == null) {
            return;
        }

        FilterOperator op = (FilterOperator) filterOperator.getSelectedItem();
        if (op == null) {
            return;
        }

        Object v1 = parseValue(filterValue.getText());
        Object v2 = parseValue(filterValue2.getText());

        FilterCondition c = new FilterCondition(f, op, v1, v2);
        filterModel.addElement(c);
        syncControllerPipeline();
    }

    private void addGroup(boolean independent) {
        DomainField f = singleCheckedField();
        if (f == null) {
            return;
        }

        // Independent → a new top-level dimension off the root; nested → a child of
        // the selected group, drilling down within each of its buckets.
        DefaultMutableTreeNode parent = independent ? groupRoot : selectedGroupNode();
        if (parent == null) {
            parent = groupRoot;
        }

        DefaultMutableTreeNode child = new DefaultMutableTreeNode(new GroupNode(f));
        groupTreeModel.insertNodeInto(child, parent, parent.getChildCount());
        groupTree.expandPath(new TreePath(parent.getPath()));
        groupTree.setSelectionPath(new TreePath(child.getPath()));
        syncControllerPipeline();
    }

    private void removeSelectedGroup() {
        DefaultMutableTreeNode node = selectedGroupNode();
        if (node == null || node == groupRoot) {
            return;
        }
        groupTreeModel.removeNodeFromParent(node);
        syncControllerPipeline();
    }

    private DefaultMutableTreeNode selectedGroupNode() {
        Object last = groupTree.getLastSelectedPathComponent();
        return last instanceof DefaultMutableTreeNode n ? n : groupRoot;
    }

    private void clearViewSteps() {
        filterModel.clear();
        groupRoot.removeAllChildren();
        groupTreeModel.reload();
    }

    private void syncControllerPipeline() {
        controller.replaceViewPipeline(toOperations());
        fireChanged();
    }

    private List<OperationSpec> toOperations() {
        List<OperationSpec> out = new ArrayList<>();

        if (!filterModel.isEmpty()) {
            OperationSpec filter = new OperationSpec();
            filter.kind = OperationKind.FILTER;
            filter.conditions = new ArrayList<>();

            for (int i = 0; i < filterModel.size(); i++) {
                filter.conditions.add(filterModel.get(i));
            }

            out.add(filter);
        }

        // Pre-order walk of the group tree: each node emits a GROUP_BY tagged with
        // its depth (root's children = 0), which ViewCompiler rebuilds into a tree.
        GroupPipelineCodec.appendOperations(groupRoot, out);
        return out;
    }

    /** Mirror the controller's pipeline (e.g. a seeded default) back into the filter
     *  list and group tree, so the controls match the rendered result. The inverse
     *  of {@link #toOperations}: depth-tagged GROUP_BY steps rebuild the tree. */
    private void syncFromPipeline() {
        filterModel.clear();

        for (OperationSpec op : controller.pipeline()) {
            if (op == null) {
                continue;
            }

            if (op.kind == OperationKind.FILTER && op.conditions != null) {
                for (FilterCondition c : op.conditions) {
                    filterModel.addElement(c);
                }
            }
        }

        GroupPipelineCodec.rebuildTree(
                groupRoot,
                groupTreeModel,
                controller.pipeline()
                                      );

        expandAllGroups();
        fireChanged();
    }

    private void expandAllGroups() {
        for (int i = 0; i < groupTree.getRowCount(); i++) {
            groupTree.expandRow(i);
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