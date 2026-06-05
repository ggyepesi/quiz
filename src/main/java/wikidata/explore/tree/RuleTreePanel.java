package wikidata.explore.tree;

import javax.swing.*;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;

public class RuleTreePanel extends JPanel {

    private RuleNode rootRuleNode;
    private DefaultMutableTreeNode rootTreeNode;
    private final DefaultTreeModel treeModel;
    private final JTree tree;

    public RuleTreePanel(RuleNode rootRuleNode) {
        super(new BorderLayout());

        this.rootRuleNode = rootRuleNode;
        this.rootTreeNode = buildTree(rootRuleNode);
        this.treeModel = new DefaultTreeModel(rootTreeNode);
        this.tree = new JTree(treeModel);

        tree.setRootVisible(true);
        tree.setRowHeight(26);
        tree.setFont(tree.getFont().deriveFont(14f));

        add(new JScrollPane(tree), BorderLayout.CENTER);

        expandAll();
    }

    public RuleNode rootRuleNode() {
        return rootRuleNode;
    }

    /**
     * Replaces the displayed rule tree with a newly loaded root node.
     * Called after deserialising/loading a config from disk.
     */
    public void setRootNode(RuleNode newRoot) {
        if (newRoot == null) {
            return;
        }

        this.rootRuleNode = newRoot;
        refresh();
    }

    public void addTreeSelectionListener(TreeSelectionListener listener) {
        tree.addTreeSelectionListener(listener);
    }

    public Object selectedUserObject() {
        DefaultMutableTreeNode node =
                (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();

        return node == null ? null : node.getUserObject();
    }

    public RuleNode selectedRuleNode() {
        Object selected =
                selectedUserObject();

        return selected instanceof RuleNode node ? node : null;
    }

    public RuleEdge selectedRuleEdge() {
        Object selected =
                selectedUserObject();

        return selected instanceof RuleEdge edge ? edge : null;
    }

    public JTree tree() {
        return tree;
    }

    public void refresh() {
        Object selected =
                selectedUserObject();

        rootTreeNode =
                buildTree(rootRuleNode);

        treeModel.setRoot(rootTreeNode);
        treeModel.reload();

        expandAll();

        /*
         * Try to preserve selection. This works best when the same RuleNode /
         * RuleEdge object instances are still present, which is normally true
         * after editing and refreshing the current in-memory tree.
         */
        if (selected != null) {
            selectUserObject(selected);
        }
    }

    public void selectRoot() {
        if (rootTreeNode == null) {
            return;
        }

        TreePath path =
                new TreePath(rootTreeNode.getPath());

        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
    }

    public boolean selectRuleNode(RuleNode ruleNode) {
        return selectUserObject(ruleNode);
    }

    public boolean selectRuleEdge(RuleEdge ruleEdge) {
        return selectUserObject(ruleEdge);
    }

    public boolean selectUserObject(Object userObject) {
        if (userObject == null || rootTreeNode == null) {
            return false;
        }

        DefaultMutableTreeNode found =
                findNodeWithUserObject(rootTreeNode, userObject);

        if (found == null) {
            return false;
        }

        TreePath path =
                new TreePath(found.getPath());

        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);

        return true;
    }

    private DefaultMutableTreeNode buildTree(RuleNode node) {
        DefaultMutableTreeNode treeNode =
                new DefaultMutableTreeNode(node);

        if (node == null) {
            return treeNode;
        }

        for (RuleEdge edge : node.edges()) {
            DefaultMutableTreeNode edgeNode =
                    new DefaultMutableTreeNode(edge);

            edgeNode.add(buildTree(edge.childNode()));
            treeNode.add(edgeNode);
        }

        return treeNode;
    }

    private DefaultMutableTreeNode findNodeWithUserObject(
            DefaultMutableTreeNode current,
            Object userObject) {

        if (current == null) {
            return null;
        }

        if (current.getUserObject() == userObject) {
            return current;
        }

        for (int i = 0; i < current.getChildCount(); i++) {
            Object child =
                    current.getChildAt(i);

            if (child instanceof DefaultMutableTreeNode childNode) {
                DefaultMutableTreeNode found =
                        findNodeWithUserObject(childNode, userObject);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }
}
