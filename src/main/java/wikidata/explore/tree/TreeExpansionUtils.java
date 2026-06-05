package wikidata.explore.tree;

import javax.swing.*;
import javax.swing.tree.TreePath;

/**
 * Prevents image/result trees from opening every descendant down to leaf nodes.
 */
public final class TreeExpansionUtils {

    private TreeExpansionUtils() {
    }

    /**
     * Shows a newly added result node without recursively expanding all
     * descendants. This avoids automatically opening image leaf children.
     */
    public static void revealNodePathOnly(JTree tree, TreePath path) {
        if (tree == null || path == null) {
            return;
        }

        TreePath parent =
                path.getParentPath();

        if (parent != null) {
            tree.expandPath(parent);
        }

        tree.scrollPathToVisible(path);
        tree.setSelectionPath(path);
    }

    /**
     * If old code called expandAll after adding children, replace it with this
     * for result/image nodes.
     */
    public static void revealWithoutExpandingChildren(
            JTree tree,
            TreePath path) {

        revealNodePathOnly(tree, path);
    }
}
