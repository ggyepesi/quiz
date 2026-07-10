package quiz.transform.pipeline.ui;

import quiz.transform.ui.OperationKind;
import quiz.transform.ui.OperationSpec;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.util.ArrayList;
import java.util.List;

public final class GroupPipelineCodec {

    private GroupPipelineCodec() {}

    public static void appendOperations(DefaultMutableTreeNode root,
                                        List<OperationSpec> out) {
        append(root, 0, out);
    }

    private static void append(DefaultMutableTreeNode node,
                               int depth,
                               List<OperationSpec> out) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child =
                    (DefaultMutableTreeNode) node.getChildAt(i);

            if (child.getUserObject() instanceof GroupNode g && g.field() != null) {
                OperationSpec op = new OperationSpec(
                        OperationKind.GROUP_BY,
                        g.field(),
                        null
                );
                op.depth = depth;
                out.add(op);
            }

            append(child, depth + 1, out);
        }
    }

    public static void rebuildTree(DefaultMutableTreeNode root,
                                   DefaultTreeModel model,
                                   List<OperationSpec> pipeline) {
        root.removeAllChildren();

        List<DefaultMutableTreeNode> path = new ArrayList<>();

        for (OperationSpec op : pipeline) {
            if (op == null
                    || op.kind != OperationKind.GROUP_BY
                    || op.field == null) {
                continue;
            }

            DefaultMutableTreeNode node =
                    new DefaultMutableTreeNode(new GroupNode(op.field));

            int depth = Math.max(0, Math.min(op.depth, path.size()));
            DefaultMutableTreeNode parent =
                    depth == 0 ? root : path.get(depth - 1);

            parent.add(node);

            while (path.size() > depth) {
                path.remove(path.size() - 1);
            }

            path.add(node);
        }

        model.reload();
    }
}