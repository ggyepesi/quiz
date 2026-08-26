package wikidata.explore.workbench;

import wikidata.explore.codegen.GeneratedViewableSourceGenerator;
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
    private final JButton importClassButton = new JButton("Copy class…");
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

    /** Keeps the model tree navigable while preventing structural edits. */
    public void setEditingEnabled(boolean enabled) {
        renameClassButton.setEnabled(enabled);
        addClassButton.setEnabled(enabled);
        importClassButton.setEnabled(enabled);
        addFieldButton.setEnabled(enabled);
        removeButton.setEnabled(enabled);
    }

    public void onImportClass(Runnable action) {
        for (java.awt.event.ActionListener listener
                : importClassButton.getActionListeners()) {
            importClassButton.removeActionListener(listener);
        }
        if (action != null) importClassButton.addActionListener(e -> action.run());
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
        // Capture the selection by NAME, not object identity: refresh() runs right
        // after copyContentsFrom (load/reload), which swaps in FRESH model objects.
        // Re-selecting the old instance by identity silently fails, leaving the field
        // editor bound to an ORPHANED old field — so edits land on the orphan while
        // save serializes the new model, and they're lost. Re-selecting by name
        // re-binds the editor to the live object.
        String selClassName = selectedClassName(selected);
        String selFieldName = selected instanceof GeneratedFieldModel f ? f.name() : null;

        java.util.Set<java.util.List<String>> expanded = expandedPaths();

        rootTreeNode = buildTree();
        treeModel.setRoot(rootTreeNode);
        treeModel.reload();

        restoreExpanded(expanded);

        if (selFieldName != null) {
            selectFieldByName(selClassName, selFieldName);
        } else if (selClassName != null) {
            selectClassByName(selClassName);
        } else {
            selectClass(projectModel.rootClass());
        }
    }

    // The class name of the current selection: a class node directly, or the class
    // owning a selected field (read off the tree path, since the field's owner may
    // already have been replaced in the model by a preceding copyContentsFrom).
    private String selectedClassName(Object selected) {
        if (selected instanceof GeneratedClassModel c) {
            return c.className();
        }
        if (selected instanceof GeneratedFieldModel) {
            javax.swing.tree.TreePath path = tree.getSelectionPath();
            if (path != null && path.getPathCount() >= 2) {
                Object parent = ((DefaultMutableTreeNode)
                        path.getPathComponent(path.getPathCount() - 2)).getUserObject();
                if (parent instanceof GeneratedClassModel c) {
                    return c.className();
                }
            }
        }
        return null;
    }

    private void selectFieldByName(String className, String fieldName) {
        for (GeneratedClassModel cls : projectModel.classes()) {
            if (className != null && !className.equals(cls.className())) {
                continue;
            }
            for (GeneratedFieldModel f : cls.fields()) {
                if (fieldName.equals(f.name())) {
                    selectField(f);
                    return;
                }
            }
        }
    }

    private void selectClassByName(String className) {
        GeneratedClassModel c = projectModel.findClass(className);
        if (c != null) {
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
        // Double-click expands, the Swing default. Nothing here claims double-click, and
        // with 0 the ONLY way to open a class was the expand handle — invisible in some
        // themes, which makes a class with fields look like a class with none.
        tree.setToggleClickCount(2);
        tree.setRowHeight(28);
        tree.setFont(tree.getFont().deriveFont(14f));
        tree.setCellRenderer(new ClassPatternTreeRenderer(projectModel));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(renameClassButton);
        buttons.add(addClassButton);
        buttons.add(importClassButton);
        buttons.add(addFieldButton);
        buttons.add(removeButton);

        // This panel lives in the narrow left split, so the button row would
        // otherwise wrap and clip "Add field"/"Remove". Keep them reachable
        // via a horizontal scrollbar.
        add(objectview.utils.swing.ScrollPaneUtils.horizontalOnly(buttons), BorderLayout.NORTH);
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

        // Selections (vocabularies / populations) shown inline, so the domain's
        // value domains are visible in the tree — not hidden behind the Selections
        // window, and clearly NOT classes.
        if (!projectModel.selections().isEmpty()) {
            DefaultMutableTreeNode selectionsNode =
                    new DefaultMutableTreeNode("Selections");
            for (Selection s : projectModel.selections()) {
                selectionsNode.add(new DefaultMutableTreeNode(s));
            }
            projectNode.add(selectionsNode);
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

        // Through the project, so every field target, base class and kind rule that
        // names this class follows the rename instead of dangling.
        projectModel.renameClass(cls.className(),
                GeneratedViewableSourceGenerator.sanitizeClassName(s));

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
                        GeneratedViewableSourceGenerator
                                .sanitizeClassName(s));

        refresh();
        selectClass(cls);
    }

    private void addField() {
        GeneratedClassModel cls = selectedClassOrRoot();

        String name = "field";
        while (true) {
            name = JOptionPane.showInputDialog(this, "Field name:", name);
            if (name == null || name.isBlank()) return;
            if (!GeneratedClassModel.isReservedFieldName(name)) break;
            JOptionPane.showMessageDialog(this,
                    "'" + name.trim() + "' already exists as a built-in "
                            + (name.trim().equalsIgnoreCase("name")
                                    ? "display-name" : "identity-QID")
                            + " field. Choose a different field name.",
                    "Reserved field name", JOptionPane.INFORMATION_MESSAGE);
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
            // The `name` field is vestigial now (identity/display comes from the
            // CanonicalSpec + the generated @Hidden name), so it's freely
            // removable — and won't be re-added (ensureNameField is gone).
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
            return;
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
        boolean restoredAny = false;
        while (i < tree.getRowCount()) {
            TreePath p = tree.getPathForRow(i);
            if (p != null && expanded.contains(pathLabels(p))) {
                tree.expandPath(p);
                restoredAny = true;
            }
            i++;
        }
        // Remembering what was open only means something within the SAME tree. Loading
        // another domain rebuilds it from different classes, so nothing matches and every
        // class ends up shut — the first build's expand-all is the honest default there.
        if (!restoredAny) {
            expandAll();
            return;
        }
        if (tree.getRowCount() > 0) {
            tree.expandRow(0); // always show the domain's classes
        }
    }

    // Shows each class node's membership PATTERN (and extends base) inline, so the
    // configuration shape — "Multi-target relation", "Type + subtypes", … — is
    // visible without opening the class. Display-only: the node's userObject (and
    // toString, used for expansion-path keys) is untouched.
    private static final class ClassPatternTreeRenderer
            extends javax.swing.tree.DefaultTreeCellRenderer {
        // Held so a referenced-only class can be labelled by the field that derives
        // it (e.g. "Derived from Nomination.forWork (P1686)") instead of
        // "Unconfigured". The project's identity is stable across reloads (its
        // contents are replaced in place), so a single reference is safe.
        private final GeneratedProjectModel project;

        ClassPatternTreeRenderer(GeneratedProjectModel project) {
            this.project = project;
        }

        @Override public Component getTreeCellRendererComponent(
                JTree tree, Object value, boolean sel, boolean expanded,
                boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(
                    tree, value, sel, expanded, leaf, row, focus);
            Object uo = value instanceof DefaultMutableTreeNode dn
                    ? dn.getUserObject() : null;
            if (uo instanceof GeneratedClassModel cls) {
                // The display alias when set; the real className follows in
                // parentheses so the identity stays visible.
                StringBuilder t = new StringBuilder(cls.displayClassName());
                if (!cls.alias().isBlank()) {
                    t.append(" (").append(cls.className()).append(')');
                }
                if (cls.hasBase()) {
                    t.append(" : ").append(cls.baseClassName());
                }
                t.append("   [").append(MembershipPattern.describe(cls, project)).append(']');
                setText(t.toString());
                setForeground(sel ? getTextSelectionColor()
                        : new Color(60, 90, 120));
            } else if (uo instanceof Selection seln) {
                String detail;
                if (seln instanceof VocabularySelection v) {
                    detail = "vocabulary · " + v.valueQids().size() + " value(s)";
                } else if (seln instanceof PopulationSelection p) {
                    detail = "population"
                            + (p.relationPid().isBlank() ? "" : " · " + p.relationPid());
                } else {
                    detail = String.valueOf(seln.kind());
                }
                setText("◇ " + seln.name() + "   [" + detail + "]");
                setForeground(sel ? getTextSelectionColor()
                        : new Color(90, 110, 60));
            }
            return this;
        }
    }
}
