package wikidata.explore.tree;

import javax.swing.tree.DefaultMutableTreeNode;

public final class TreeNodeUserObjects {

    private TreeNodeUserObjects() {
    }

    public static Object userObject(Object treeValue) {
        if (treeValue instanceof DefaultMutableTreeNode node) {
            return node.getUserObject();
        }

        return treeValue;
    }
}
