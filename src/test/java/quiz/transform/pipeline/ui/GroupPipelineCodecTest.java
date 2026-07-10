package quiz.transform.pipeline.ui;

import org.junit.jupiter.api.Test;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.OperationKind;
import quiz.transform.ui.OperationSpec;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The group tree ↔ depth-tagged GROUP_BY pipeline is a pre-order encoding: encode
 *  (appendOperations) and decode (rebuildTree) must round-trip. */
class GroupPipelineCodecTest {

    private static DomainField f(String name) {
        return new DomainField("Nomination", name, false, false);
    }

    private static OperationSpec group(String field, int depth) {
        OperationSpec op = new OperationSpec(OperationKind.GROUP_BY, f(field), null);
        op.depth = depth;
        return op;
    }

    @Test void appendsPreOrderWithDepths() {
        // category -> [year, won]: two nested siblings under category.
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Groups");
        DefaultMutableTreeNode cat = new DefaultMutableTreeNode(new GroupNode(f("category")));
        cat.add(new DefaultMutableTreeNode(new GroupNode(f("year"))));
        cat.add(new DefaultMutableTreeNode(new GroupNode(f("won"))));
        root.add(cat);

        List<OperationSpec> ops = new ArrayList<>();
        GroupPipelineCodec.appendOperations(root, ops);

        assertEquals(3, ops.size());
        assertEquals("category", ops.get(0).field.field());
        assertEquals(0, ops.get(0).depth);
        assertEquals("year", ops.get(1).field.field());
        assertEquals(1, ops.get(1).depth);
        assertEquals("won", ops.get(2).field.field());
        assertEquals(1, ops.get(2).depth);
    }

    @Test void treeAndPipelineRoundTrip() {
        // A nested chain plus an independent second dimension off the root.
        List<OperationSpec> ops = List.of(
                group("category", 0),
                group("year", 1),
                group("language", 0));

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Groups");
        GroupPipelineCodec.rebuildTree(root, new DefaultTreeModel(root), ops);

        List<OperationSpec> out = new ArrayList<>();
        GroupPipelineCodec.appendOperations(root, out);

        assertEquals(ops.size(), out.size());
        for (int i = 0; i < ops.size(); i++) {
            assertEquals(ops.get(i).field.field(), out.get(i).field.field());
            assertEquals(ops.get(i).depth, out.get(i).depth);
        }
    }
}
