package wikidata.explore.workbench;

import wikidata.explore.codegen.GeneratedQuizableSourceGenerator;
import wikidata.explore.model.*;

import javax.swing.*;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;

public class SingleRootClassModelPanel extends JPanel {

    private final GeneratedProjectModel projectModel;
    private DefaultMutableTreeNode rootTreeNode;
    private final DefaultTreeModel treeModel;
    private final JTree tree;

    private final JButton renameClassButton = new JButton("Rename class");
    private final JButton addClassButton = new JButton("Add class");
    private final JButton addFieldButton = new JButton("Add field");
    private final JButton removeButton = new JButton("Remove");

    public SingleRootClassModelPanel(GeneratedProjectModel projectModel) {
        super(new BorderLayout(4, 4));

        this.projectModel =
                projectModel == null
                        ? GeneratedProjectModel.constellationDemo()
                        : projectModel;

        this.rootTreeNode = buildTree();
        this.treeModel = new DefaultTreeModel(rootTreeNode);
        this.tree = new JTree(treeModel);

        buildUi();
        refresh();
    }

    public GeneratedProjectModel projectModel() {
        return projectModel;
    }

    public GeneratedClassModel rootClass() {
        return projectModel.rootClass();
    }

    public void addTreeSelectionListener(TreeSelectionListener listener) {
        tree.addTreeSelectionListener(listener);
    }

    public Object selectedUserObject() {
        DefaultMutableTreeNode n =
                (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        return n == null ? null : n.getUserObject();
    }

    public GeneratedClassModel selectedClassOrRoot() {
        Object selected = selectedUserObject();

        if (selected instanceof GeneratedClassModel c) {
            return c;
        }

        if (selected instanceof GeneratedFieldModel f) {
            return owningClassOf(f);
        }

        return projectModel.rootClass();
    }

    public void refresh() {
        Object selected = selectedUserObject();
        java.util.Set<java.util.List<String>> expanded = expandedPaths();

        for (GeneratedClassModel c : projectModel.classes()) {
            c.ensureNameField();
        }

        rootTreeNode = buildTree();
        treeModel.setRoot(rootTreeNode);
        treeModel.reload();

        restoreExpanded(expanded);

        if (selected instanceof GeneratedFieldModel f) {
            selectField(f);
        } else if (selected instanceof GeneratedClassModel c) {
            selectClass(c);
        } else {
            selectClass(projectModel.rootClass());
        }
    }

    public void selectField(GeneratedFieldModel field) {
        DefaultMutableTreeNode node =
                findNodeForUserObject(rootTreeNode, field);
        if (node != null) {
            selectNode(node);
        }
    }

    public void selectClass(GeneratedClassModel cls) {
        DefaultMutableTreeNode node =
                findNodeForUserObject(rootTreeNode, cls);
        if (node != null) {
            selectNode(node);
        }
    }

    private void buildUi() {
        tree.setRootVisible(true);
        tree.setToggleClickCount(0);
        tree.setRowHeight(28);
        tree.setFont(tree.getFont().deriveFont(14f));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(renameClassButton);
        buttons.add(addClassButton);
        buttons.add(addFieldButton);
        buttons.add(removeButton);

        // This panel lives in the narrow left split, so the button row would
        // otherwise wrap and clip "Add field"/"Remove". Keep them reachable
        // via a horizontal scrollbar.
        add(aux.ScrollPaneUtils.horizontalOnly(buttons), BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);

        renameClassButton.addActionListener(e -> renameClass());
        addClassButton.addActionListener(e -> addClass());
        addFieldButton.addActionListener(e -> addField());
        removeButton.addActionListener(e -> removeSelected());
    }

    private DefaultMutableTreeNode buildTree() {
        DefaultMutableTreeNode projectNode =
                new DefaultMutableTreeNode("Domain: " + projectModel.name());

        for (GeneratedClassModel cls : projectModel.classes()) {
            cls.ensureNameField();

            DefaultMutableTreeNode classNode =
                    new DefaultMutableTreeNode(cls);

            for (GeneratedFieldModel f : cls.fields()) {
                DefaultMutableTreeNode fieldNode =
                        new DefaultMutableTreeNode(f);

                for (GeneratedFieldModel child : f.fields()) {
                    fieldNode.add(new DefaultMutableTreeNode(child));
                }

                classNode.add(fieldNode);
            }

            projectNode.add(classNode);
        }

        return projectNode;
    }

    private void renameClass() {
        GeneratedClassModel cls = selectedClassOrRoot();

        String s =
                JOptionPane.showInputDialog(
                        this,
                        "Class name:",
                        cls.className());

        if (s == null || s.isBlank()) {
            return;
        }

        cls.className(
                GeneratedQuizableSourceGenerator.sanitizeClassName(s));

        refresh();
        selectClass(cls);
    }

    private void addClass() {
        String s =
                JOptionPane.showInputDialog(
                        this,
                        "New class name:",
                        "Star");

        if (s == null || s.isBlank()) {
            return;
        }

        GeneratedClassModel cls =
                projectModel.getOrCreateClass(
                        GeneratedQuizableSourceGenerator
                                .sanitizeClassName(s));

        refresh();
        selectClass(cls);
    }

    private void addField() {
        GeneratedClassModel cls = selectedClassOrRoot();

        String name =
                JOptionPane.showInputDialog(
                        this,
                        "Field name:",
                        "field");

        if (name == null || name.isBlank()) {
            return;
        }

        GeneratedFieldModel f =
                cls.addField(
                        name,
                        FieldType.AUTO,
                        FieldCardinality.AUTO);

        refresh();
        selectField(f);
    }

    private void removeSelected() {
        Object selected = selectedUserObject();

        if (selected instanceof GeneratedFieldModel f) {
            if (f.isNameField()) {
                JOptionPane.showMessageDialog(
                        this,
                        "The name field is required and cannot be removed.");
                return;
            }

            GeneratedClassModel owner = owningClassOf(f);
            if (owner != null) {
                owner.fields().remove(f);
            }

            refresh();
            return;
        }

        if (selected instanceof GeneratedClassModel c) {
            if (c == projectModel.rootClass()) {
                JOptionPane.showMessageDialog(
                        this,
                        "The root class cannot be removed.");
                return;
            }

            projectModel.removeClass(c);
            refresh();
        }
    }

    private GeneratedClassModel owningClassOf(GeneratedFieldModel f) {
        if (f == null) {
            return null;
        }

        for (GeneratedClassModel cls : projectModel.classes()) {
            if (cls.fields().contains(f)) {
                return cls;
            }
        }

        return null;
    }

    private void selectNode(DefaultMutableTreeNode node) {
        TreePath path = new TreePath(node.getPath());
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
    }

    private DefaultMutableTreeNode findNodeForUserObject(
            DefaultMutableTreeNode node,
            Object target) {

        if (node == null || target == null) {
            return null;
        }

        if (node.getUserObject() == target) {
            return node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode found =
                    findNodeForUserObject(
                            (DefaultMutableTreeNode) node.getChildAt(i),
                            target);

            if (found != null) {
                return found;
            }
        }

        return null;
    }

    private void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    // The currently-expanded paths, keyed by their node labels — so expansion
    // survives a refresh() rebuild (which creates fresh node instances).
    private java.util.Set<java.util.List<String>> expandedPaths() {
        java.util.Set<java.util.List<String>> out = new java.util.HashSet<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath p = tree.getPathForRow(i);
            if (p != null && tree.isExpanded(p)) {
                out.add(pathLabels(p));
            }
        }
        return out;
    }

    private static java.util.List<String> pathLabels(TreePath p) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (Object o : p.getPath()) {
            labels.add(String.valueOf(o));
        }
        return labels;
    }

    // Re-expand the paths that were open before the rebuild; collapsed branches
    // stay collapsed. First build (nothing captured) expands all as a default.
    private void restoreExpanded(java.util.Set<java.util.List<String>> expanded) {
        if (expanded.isEmpty()) {
            expandAll();
            return;
        }
        int i = 0;
        while (i < tree.getRowCount()) {
            TreePath p = tree.getPathForRow(i);
            if (p != null && expanded.contains(pathLabels(p))) {
                tree.expandPath(p);
            }
            i++;
        }
        if (tree.getRowCount() > 0) {
            tree.expandRow(0); // always show the domain's classes
        }
    }
}