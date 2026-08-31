package wikidata.explore.workbench;

import datasource.schema.FieldType;

import wikidata.explore.codegen.GeneratedViewableSourceGenerator;
import wikidata.explore.model.*;

import javax.swing.*;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class SingleRootClassModelPanel extends JPanel {

    public enum ConfigurationSection {
        VOCABULARIES("Vocabularies / populations"),
        USES("Uses");
        private final String label;
        ConfigurationSection(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final GeneratedProjectModel projectModel;
    private DefaultMutableTreeNode rootTreeNode;
    private final DefaultTreeModel treeModel;
    private final JTree tree;
    private final List<TreeSelectionListener> selectionListeners = new ArrayList<>();
    /** Tree-model replacement temporarily clears selection. That is Swing layout state,
     * not a user navigation, and must never make an editor apply stale values. */
    private boolean refreshing;
    private boolean editingEnabled = true;

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
        this.tree.addTreeSelectionListener(event -> {
            if (refreshing) return;
            updateActionState();
            for (TreeSelectionListener listener : List.copyOf(selectionListeners)) {
                listener.valueChanged(event);
            }
        });

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
        if (listener != null) selectionListeners.add(listener);
    }

    /** Keeps the model tree navigable while preventing structural edits. */
    public void setEditingEnabled(boolean enabled) {
        editingEnabled = enabled;
        addClassButton.setEnabled(enabled);
        importClassButton.setEnabled(enabled);
        updateActionState();
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

    /** Only model leaves have configuration editors. The domain and grouping rows
     * are navigation context and selecting them must not blank or flush an editor. */
    public static boolean isConfigurable(Object value) {
        return value instanceof GeneratedClassModel
                || value instanceof GeneratedFieldModel
                || value instanceof Selection
                // Only the vocabulary section opens an editor. Uses reports what was
                // adopted and configures nothing, so it is inspection, not a surface.
                || value == ConfigurationSection.VOCABULARIES
                || value instanceof GeneratedProjectModel;
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
        String selSelectionName = selected instanceof Selection selection
                ? selection.name() : null;
        ConfigurationSection selSection = selected instanceof ConfigurationSection section
                ? section : null;
        boolean selDomain = selected instanceof GeneratedProjectModel;

        java.util.Set<java.util.List<String>> expanded = expandedPaths();

        refreshing = true;
        try {
            rootTreeNode = buildTree();
            treeModel.setRoot(rootTreeNode);
            treeModel.reload();

            restoreExpanded(expanded);

            if (selDomain) {
                selectNode(rootTreeNode);
            } else if (selSection != null) {
                selectSection(selSection);
            } else if (selSelectionName != null) {
                selectSelectionByName(selSelectionName);
            } else if (selFieldName != null) {
                selectFieldByName(selClassName, selFieldName);
            } else if (selClassName != null) {
                selectClassByName(selClassName);
            } else {
                selectClass(projectModel.rootClass());
            }
        } finally {
            refreshing = false;
        }

        // Consumers care about the selected model object after refresh, not the
        // transient clear/reselect events used to rebuild the JTree. Publish exactly
        // that stable state even when Swing considers its row path unchanged.
        TreePath selectedPath = tree.getSelectionPath();
        if (selectedPath != null) {
            javax.swing.event.TreeSelectionEvent stable =
                    new javax.swing.event.TreeSelectionEvent(
                            tree, selectedPath, true, null, selectedPath);
            for (TreeSelectionListener listener : List.copyOf(selectionListeners)) {
                listener.valueChanged(stable);
            }
        }
        updateActionState();
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

    private void selectSelectionByName(String name) {
        Selection selection = projectModel.findSelection(name);
        if (selection != null) {
            selectSelection(selection);
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

    public void selectSelection(Selection selection) {
        DefaultMutableTreeNode node = findNodeForUserObject(rootTreeNode, selection);
        if (node != null) selectNode(node);
    }

    private void selectSection(ConfigurationSection section) {
        DefaultMutableTreeNode node = findNodeForUserObject(rootTreeNode, section);
        if (node != null) selectNode(node);
    }

    private void updateActionState() {
        Object selected = selectedUserObject();
        boolean classContext = selected instanceof GeneratedClassModel
                || selected instanceof GeneratedFieldModel;
        boolean vocabulary = selected instanceof VocabularySelection;
        boolean vocabularyContext = vocabulary
                || selected == ConfigurationSection.VOCABULARIES;

        renameClassButton.setText(vocabulary ? "Rename vocabulary" : "Rename class");
        addClassButton.setText(vocabularyContext ? "Add vocabulary" : "Add class");

        // An adopted class's field configuration belongs to the model it came from.
        // The class itself may still be removed — dropping an adoption is this
        // project's decision — so only the field actions ask the lock.
        GeneratedClassModel owningClass = selected instanceof GeneratedClassModel c
                ? c
                : selected instanceof GeneratedFieldModel f ? owningClassOf(f) : null;
        boolean fieldsLocked = owningClass != null && owningClass.fieldsLocked();

        renameClassButton.setEnabled(editingEnabled && (classContext || vocabulary)
                && !(owningClass != null && owningClass.nameLocked()));
        addClassButton.setEnabled(editingEnabled);
        importClassButton.setEnabled(editingEnabled && classContext);
        addFieldButton.setEnabled(editingEnabled && classContext && !fieldsLocked);
        removeButton.setEnabled(editingEnabled && (classContext || vocabulary)
                && !(fieldsLocked && selected instanceof GeneratedFieldModel));
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

        renameClassButton.addActionListener(e -> renameSelected());
        addClassButton.addActionListener(e -> addSelectedKind());
        addFieldButton.addActionListener(e -> addField());
        removeButton.addActionListener(e -> removeSelected());
    }

    private void renameSelected() {
        if (selectedUserObject() instanceof VocabularySelection vocabulary) {
            renameVocabulary(vocabulary);
        } else {
            renameClass();
        }
    }

    private void addSelectedKind() {
        Object selected = selectedUserObject();
        if (selected instanceof VocabularySelection
                || selected == ConfigurationSection.VOCABULARIES) {
            addVocabulary();
        } else {
            addClass();
        }
    }

    private void renameVocabulary(VocabularySelection vocabulary) {
        String name = JOptionPane.showInputDialog(
                this, "Vocabulary name:", vocabulary.name());
        if (name == null) return;
        if (!projectModel.renameSelection(vocabulary.name(), name)) {
            JOptionPane.showMessageDialog(this,
                    "A class or vocabulary/population named '" + name.trim()
                            + "' already exists, or the name is blank.",
                    "Cannot rename vocabulary", JOptionPane.WARNING_MESSAGE);
            return;
        }
        refresh();
        selectSelection(vocabulary);
    }

    private void addVocabulary() {
        String name = JOptionPane.showInputDialog(
                this, "New vocabulary name:", "Vocabulary");
        if (name == null || name.isBlank()) return;
        String clean = name.trim();
        if (projectModel.findClass(clean) != null
                || projectModel.findSelection(clean) != null) {
            JOptionPane.showMessageDialog(this,
                    "A class or vocabulary/population named '" + clean
                            + "' already exists.",
                    "Cannot add vocabulary", JOptionPane.WARNING_MESSAGE);
            return;
        }
        VocabularySelection created = new VocabularySelection(clean);
        projectModel.addSelection(created);
        refresh();
        selectSelection(created);
    }

    private DefaultMutableTreeNode buildTree() {
        DefaultMutableTreeNode projectNode =
                new DefaultMutableTreeNode(projectModel);

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

        // Always present: this is also how an empty domain creates its FIRST
        // vocabulary now that configuration lives in the tree rather than a header button.
        DefaultMutableTreeNode selectionsNode = new DefaultMutableTreeNode(
                ConfigurationSection.VOCABULARIES);
        for (Selection s : projectModel.selections()) {
            selectionsNode.add(new DefaultMutableTreeNode(s));
        }
        projectNode.add(selectionsNode);

        // Derived from the adopted classes, so it is present exactly when something has
        // been adopted. Nothing is configured here — it reports what this project took
        // from elsewhere and where each class came from.
        List<ModelUse> uses = ModelUse.of(projectModel);
        if (!uses.isEmpty()) {
            DefaultMutableTreeNode usesNode =
                    new DefaultMutableTreeNode(ConfigurationSection.USES);
            for (ModelUse use : uses) {
                usesNode.add(new DefaultMutableTreeNode(use));
            }
            projectNode.add(usesNode);
        }

        return projectNode;
    }

    private void renameClass() {
        Object selected = selectedUserObject();
        if (!(selected instanceof GeneratedClassModel)
                && !(selected instanceof GeneratedFieldModel)) return;
        GeneratedClassModel cls = selectedClassOrRoot();

        String s =
                JOptionPane.showInputDialog(
                        this,
                        "Class name:",
                        cls.className());

        if (s == null) {
            return;
        }
        if (s.isBlank()) {
            JOptionPane.showMessageDialog(this, "A class name is required.",
                    "Cannot rename class", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Through the project, so every field target, base class and kind rule that
        // names this class follows the rename instead of dangling.
        String requested = GeneratedViewableSourceGenerator.sanitizeClassName(s);
        if (!projectModel.renameClass(cls.className(), requested)) {
            JOptionPane.showMessageDialog(this,
                    "A class or vocabulary/population named '" + requested
                            + "' already exists.",
                    "Cannot rename class", JOptionPane.WARNING_MESSAGE);
            return;
        }

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

        if (selected instanceof VocabularySelection vocabulary) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "Delete vocabulary " + vocabulary.name() + "?",
                    "Delete vocabulary", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;
            if (!projectModel.removeSelection(vocabulary.name())) {
                JOptionPane.showMessageDialog(this,
                        "Cannot delete " + vocabulary.name()
                                + ": the model still references it.",
                        "Cannot delete vocabulary", JOptionPane.WARNING_MESSAGE);
                return;
            }
            refresh();
            return;
        }

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
            if (uo instanceof GeneratedProjectModel model) {
                // The kind names itself. Spelling it here made the tree call every
                // project a Domain, including the models the New… dialog had just
                // asked the user to choose.
                setText(model.projectKind() + ": " + model.name());
                setFont(getFont().deriveFont(Font.BOLD));
            } else if (uo instanceof GeneratedClassModel cls) {
                // The display alias when set; the real className follows in
                // parentheses so the identity stays visible.
                StringBuilder t = new StringBuilder(cls.displayClassName());
                if (!cls.alias().isBlank()) {
                    t.append(" (").append(cls.className()).append(')');
                }
                if (cls.hasBase()) {
                    t.append(" : ").append(cls.baseClassName());
                }
                if (!cls.originModel().isBlank()) {
                    t.append(" ← ").append(cls.originModel());
                }
                t.append("   [").append(MembershipPattern.describe(cls, project)).append(']');
                setText(t.toString());
                setForeground(sel ? getTextSelectionColor()
                        : new Color(60, 90, 120));
            } else if (uo instanceof ModelUse use) {
                setText(use.modelName() + "   ["
                        + String.join(", ", use.classNames()) + "]");
                setForeground(sel ? getTextSelectionColor()
                        : new Color(110, 110, 110));
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
