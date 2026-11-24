package domain.workbench.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration navigation and action/result navigation share one neutral shell.
 * The shell reveals the detail supplied for a stable node id; it has no action or
 * model callback of its own, and expanding the inventory is inspection only.
 */
class TreeAndDetailPanelTest {

    @Test void selectionRevealsTheSuppliedDetailAndExpansionDoesNothingElse()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TreeAndDetailPanel<String> shell =
                    new TreeAndDetailPanel<>("test.tree-detail", "Configuration");
            JPanel field = detail("field");
            JPanel source = detail("source");
            shell.addRoot("field", "members", field);
            shell.addChild("field", "source", "Primary source", source);
            List<String> selected = new ArrayList<>();
            shell.setSelectionListener(value -> selected.add(value.id()));

            shell.expand("field");
            assertEquals(List.of(), selected,
                    "expanding is inspection and must not select or invoke the host");

            shell.select("source");
            assertEquals(List.of("source"), selected);
            assertTrue(source.isVisible());
            assertSame(source, visibleDetail(shell));
        });
    }

    @Test void aSharedDetailRemainsWhileAnyNodeStillUsesIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TreeAndDetailPanel<String> shell =
                    new TreeAndDetailPanel<>("test.tree-detail", "Configuration");
            JPanel shared = detail("shared");
            shell.addRoot("action", "Action", shared);
            shell.addChild("action", "result", "Result", shared);

            assertEquals(List.of("result"), shell.removeChildren("action"));
            shell.select("action");
            assertSame(shared, visibleDetail(shell),
                    "removing a result must not remove its action's shared detail card");
        });
    }

    /** Adding a sibling under an expanded node used to reload that node, collapsing
     *  "Produced by" and clearing the selected "Primary" in the reported shape. */
    @Test void addingANodeKeepsExpansionAndSelection() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            TreeAndDetailPanel<String> shell = new TreeAndDetailPanel<>("test", "Outline");
            shell.addRoot("field", "field", new javax.swing.JPanel());
            shell.addChild("field", "produced", "Produced by", new javax.swing.JPanel());
            shell.addChild("produced", "primary", "Primary", new javax.swing.JPanel());
            shell.expand("field");
            shell.expand("produced");
            shell.select("primary");

            shell.addChild("field", "refine", "Refine", new javax.swing.JPanel());

            org.junit.jupiter.api.Assertions.assertEquals(4, shell.tree().getRowCount(),
                    "Produced by stays expanded and Refine is shown");
            org.junit.jupiter.api.Assertions.assertEquals("Primary",
                    String.valueOf(((javax.swing.tree.DefaultMutableTreeNode)
                            shell.tree().getLastSelectedPathComponent()).getUserObject()));
        });
    }

    @Test void stableNodeIdsCannotBeAddedTwice() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TreeAndDetailPanel<String> shell =
                    new TreeAndDetailPanel<>("test.tree-detail", "Configuration");
            shell.addRoot("field", "members", detail("first"));
            assertThrows(IllegalArgumentException.class,
                    () -> shell.addRoot("field", "other", detail("second")));
        });
    }

    private static JPanel detail(String name) {
        JPanel panel = new JPanel();
        panel.setName(name);
        panel.add(new JLabel(name));
        return panel;
    }

    private static JPanel visibleDetail(TreeAndDetailPanel<?> shell) {
        return java.util.Arrays.stream(shell.detailHost().getComponents())
                .filter(java.awt.Component::isVisible)
                .map(JPanel.class::cast).findFirst().orElseThrow();
    }
}
