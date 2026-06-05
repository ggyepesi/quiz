package wikidata.explore.tree;

import wikidata.explore.model.*;

import javax.swing.*;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;

/**
 * Left panel: visible generated class model.
 */
public class SingleRootClassModelPanel extends JPanel {

    private final GeneratedProjectModel projectModel;
    private DefaultMutableTreeNode rootTreeNode;
    private final DefaultTreeModel treeModel;
    private final JTree tree;

    private final JButton renameClassButton = new JButton("Rename class");
    private final JButton addFieldButton = new JButton("Add field");
    private final JButton removeFieldButton = new JButton("Remove field");

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

    public void refresh() {
        rootTreeNode = buildTree();
        treeModel.setRoot(rootTreeNode);
        treeModel.reload();
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    private void buildUi() {
        tree.setRootVisible(true);
        tree.setRowHeight(28);
        tree.setFont(tree.getFont().deriveFont(14f));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(renameClassButton);
        buttons.add(addFieldButton);
        buttons.add(removeFieldButton);

        add(buttons, BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);

        renameClassButton.addActionListener(e -> renameClass());
        addFieldButton.addActionListener(e -> addField());
        removeFieldButton.addActionListener(e -> removeSelectedField());
    }

    private DefaultMutableTreeNode buildTree() {
        GeneratedClassModel root = projectModel.rootClass();

        DefaultMutableTreeNode rootNode =
                new DefaultMutableTreeNode(root);

        for (GeneratedFieldModel f : root.fields()) {
            DefaultMutableTreeNode fieldNode =
                    new DefaultMutableTreeNode(f);

            for (GeneratedFieldModel child : f.fields()) {
                fieldNode.add(new DefaultMutableTreeNode(child));
            }

            rootNode.add(fieldNode);
        }

        return rootNode;
    }

    private void renameClass() {
        String s =
                JOptionPane.showInputDialog(
                        this,
                        "Root class name:",
                        rootClass().className());

        if (s == null || s.isBlank()) {
            return;
        }

        rootClass().className(s);
        refresh();
    }

    private void addField() {
        String name =
                JOptionPane.showInputDialog(
                        this,
                        "Field name:",
                        "field");

        if (name == null || name.isBlank()) {
            return;
        }

        rootClass().addField(
                name,
                FieldType.AUTO,
                FieldCardinality.AUTO);

        refresh();
    }

    private void removeSelectedField() {
        Object selected =
                selectedUserObject();

        if (!(selected instanceof GeneratedFieldModel f)) {
            return;
        }

        if (f.isNameField()) {
            JOptionPane.showMessageDialog(
                    this,
                    "The name field is required and cannot be removed.");
            return;
        }

        rootClass().fields().remove(f);
        refresh();
    }
}
