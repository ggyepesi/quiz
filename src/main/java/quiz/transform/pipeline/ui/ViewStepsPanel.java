package quiz.transform.pipeline.ui;

import quiz.Quizable;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.OperationKind;
import quiz.transform.ui.OperationSpec;
import quiz.transform.ui.TransformController;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public final class ViewStepsPanel extends JPanel {

    public interface Listener {
        void viewStepsChanged();
    }

    private final TransformController controller;
    private final Listener listener;

    private final JComboBox<String> memberTypeCombo = new JComboBox<>();

    private final JPanel fieldEditorHolder = new JPanel(new BorderLayout());
    private quiz.ui.viewconfig.QuizablePanelConfigEditor fieldEditor;

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
            rebuildFieldEditor();
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

        reloadOperators();
        filterOperator.addActionListener(e -> updateFilterValueEnablement());
        updateFilterValueEnablement();

        // Seed a ready-to-run default (e.g. Oscar winners by category by year) and
        // mirror it into the controls, so the left panel matches the first render;
        // otherwise just select the first member type.
        if (controller.seedDefault()) {
            selectComboSilently(controller.selectedType());
            rebuildFieldEditor();
            syncFromPipeline();
        } else if (memberTypeCombo.getItemCount() > 0) {
            controller.selectType((String) memberTypeCombo.getSelectedItem());
            rebuildFieldEditor();
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
        p.setBorder(BorderFactory.createTitledBorder("Fields"));
        p.add(fieldEditorHolder, BorderLayout.CENTER);
        return p;
    }

    private JComponent stepsBlock() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Filter", filterPanel());
        tabs.addTab("Groups", groupPanel());
        return tabs;
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

        p.add(row, BorderLayout.NORTH);
        p.add(new JScrollPane(groupTree), BorderLayout.CENTER);
        return p;
    }

    private void rebuildFieldEditor() {
        String type = controller.selectedType();
        fieldEditorHolder.removeAll();

        Quizable sample = type == null ? null : controller.sampleOf(type);
        if (sample == null) {
            fieldEditor = null;
            fieldEditorHolder.add(new JLabel("  No sample instance."), BorderLayout.NORTH);
        } else {
            quiz.ui.viewconfig.QuizablePanelConfig cfg =
                    quiz.ui.viewconfig.QuizablePanelConfig.of(sampleClass(sample));
            cfg.setAllFields(false);

            fieldEditor = new quiz.ui.viewconfig.QuizablePanelConfigEditor(cfg, sample);
            fieldEditor.setHiddenFields(controller.structuralFields(type));
            fieldEditor.setFieldTypes(controller.fieldTypes(type));
            fieldEditorHolder.add(fieldEditor, BorderLayout.CENTER);
        }

        fieldEditorHolder.revalidate();
        fieldEditorHolder.repaint();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Quizable> sampleClass(Quizable q) {
        return (Class<? extends Quizable>) q.getClass();
    }

    private List<DomainField> checkedFields() {
        String type = controller.selectedType();
        if (type == null || fieldEditor == null) {
            return List.of();
        }
        return controller.resolveFields(type, fieldEditor.selectedFieldPaths());
    }

    private DomainField singleCheckedField() {
        List<DomainField> checked = checkedFields();
        if (checked.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Check one field first.");
            return null;
        }
        return checked.get(0);
    }

    private void reloadOperators() {
        filterOperator.removeAllItems();
        for (FilterOperator op : FilterOperator.values()) {
            filterOperator.addItem(op);
        }
    }

    /** Enable only the value field(s) the selected operator uses: none for the
     *  unary predicates (is true / is empty …), both for BETWEEN, else the first. */
    private void updateFilterValueEnablement() {
        FilterOperator op = (FilterOperator) filterOperator.getSelectedItem();
        boolean unary = op == FilterOperator.IS_TRUE || op == FilterOperator.IS_FALSE
                || op == FilterOperator.IS_EMPTY || op == FilterOperator.IS_NOT_EMPTY;
        boolean between = op == FilterOperator.BETWEEN;
        filterValue.setEnabled(!unary);
        filterValue2.setEnabled(between);
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
        appendGroupOperations(groupRoot, 0, out);
        return out;
    }

    private void appendGroupOperations(DefaultMutableTreeNode node, int depth,
                                       List<OperationSpec> out) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child =
                    (DefaultMutableTreeNode) node.getChildAt(i);

            if (child.getUserObject() instanceof GroupNode g && g.field() != null) {
                OperationSpec op = new OperationSpec(
                        OperationKind.GROUP_BY, g.field(), null);
                op.depth = depth;
                out.add(op);
            }

            appendGroupOperations(child, depth + 1, out);
        }
    }

    /** Mirror the controller's pipeline (e.g. a seeded default) back into the filter
     *  list and group tree, so the controls match the rendered result. The inverse
     *  of {@link #toOperations}: depth-tagged GROUP_BY steps rebuild the tree. */
    private void syncFromPipeline() {
        filterModel.clear();
        groupRoot.removeAllChildren();

        List<DefaultMutableTreeNode> path = new ArrayList<>();   // path.get(d) = open node at depth d
        for (OperationSpec op : controller.pipeline()) {
            if (op == null) {
                continue;
            }
            if (op.kind == OperationKind.FILTER && op.conditions != null) {
                for (FilterCondition c : op.conditions) {
                    filterModel.addElement(c);
                }
            } else if (op.kind == OperationKind.GROUP_BY && op.field != null) {
                DefaultMutableTreeNode node =
                        new DefaultMutableTreeNode(new GroupNode(op.field));
                int depth = Math.max(0, Math.min(op.depth, path.size()));
                DefaultMutableTreeNode parent = depth == 0 ? groupRoot : path.get(depth - 1);
                parent.add(node);
                while (path.size() > depth) {
                    path.remove(path.size() - 1);
                }
                path.add(node);
            }
        }

        groupTreeModel.reload();
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