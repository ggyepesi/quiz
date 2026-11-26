package domain.workbench.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An action tree used to be rebuilt independently by each workbench. This pins the
 * reusable contract: navigation reveals but never runs, and the tree and the operation's
 * real result tabs are two controls over one selected result.
 */
class WorkbenchActionResultNavigatorTest {

    @Test void actionAndResultNavigationUsesTheNeutralTreeAndDetailShell()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkbenchActionResultNavigator navigator =
                    new WorkbenchActionResultNavigator();
            TreeAndDetailPanel<?> shell = find(navigator, TreeAndDetailPanel.class);

            assertNotNull(shell);
            assertSame(shell.tree(), navigator.tree(),
                    "the action navigator must expose the shared shell's one tree");
        });
    }

    @Test void theResultWorkAreaGetsWidthBeforeTheNavigationTree() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkbenchActionResultNavigator navigator =
                    new WorkbenchActionResultNavigator();
            JSplitPane split = named(navigator, "workbench.action-result.split",
                    JSplitPane.class);
            JPanel detail = (JPanel) split.getRightComponent();

            assertEquals(220, split.getDividerLocation());
            assertEquals(0.0, split.getResizeWeight());
            assertEquals("workbench.action-result.detail", detail.getName());
            assertTrue(detail.getMinimumSize().width >= 520,
                    "the rightmost action/result pane must not collapse into a strip");
        });
    }

    @Test void anActionPublishesItsExactTabsAndBothResultSelectorsStayInSync()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkbenchActionResultNavigator navigator =
                    new WorkbenchActionResultNavigator();
            AtomicInteger reveals = new AtomicInteger();
            var source = navigator.addSource("wikipedia", "Wikipedia", null);
            JPanel operation = new JPanel();
            var action = navigator.addAction(source, "fetch", "Fetch articles",
                    operation, reveals::incrementAndGet);

            navigator.selectAction(action);
            assertEquals(1, reveals.get(),
                    "selection invokes only the supplied reveal callback; the navigator "
                            + "has no execution callback");

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Articles — 2", new JLabel("articles"));
            tabs.addTab("Sections — 9", new JLabel("sections"));
            navigator.publishResults(action, tabs, 0);

            DefaultMutableTreeNode actionNode = selectedParent(navigator.tree());
            assertEquals(List.of("Articles — 2", "Sections — 9"),
                    children(actionNode));
            assertEquals("Articles — 2", selected(navigator.tree()).toString());

            select(navigator.tree(), child(actionNode, "Sections — 9"));
            assertEquals(1, tabs.getSelectedIndex());

            tabs.setSelectedIndex(0);
            assertEquals("Articles — 2", selected(navigator.tree()).toString());
        });
    }

    @Test void oneMutableResultTabSetBelongsToOnlyItsLatestProducingAction()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkbenchActionResultNavigator navigator =
                    new WorkbenchActionResultNavigator();
            var source = navigator.addSource("wikipedia", "Wikipedia", null);
            JPanel operation = new JPanel();
            var retained = navigator.addAction(source, "retained", "Analyze retained",
                    operation, null);
            var fetched = navigator.addAction(source, "fetch", "Fetch articles",
                    operation, null);
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Articles — 2", new JPanel());

            navigator.publishResults(retained, tabs, 0);
            navigator.publishResults(fetched, tabs, 0);

            DefaultMutableTreeNode sourceNode = (DefaultMutableTreeNode)
                    ((DefaultMutableTreeNode) navigator.tree().getModel().getRoot())
                            .getChildAt(0);
            assertEquals(0, child(sourceNode, "Analyze retained").getChildCount());
            assertEquals(1, child(sourceNode, "Fetch articles").getChildCount());
        });
    }

    @Test void openingAnAlreadySelectedResultTabInitializesItOnce() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var navigator = new WorkbenchActionResultNavigator();
            var source = navigator.addSource("project", "Project", null);
            var other = navigator.addAction(source, "other", "Another action", new JPanel(), null);
            var tabs = new WorkbenchResultViews();
            var load = navigator.addAction(source, "load", "Load instances", tabs, null);
            var initialized = new AtomicInteger();
            tabs.addLazyTab("Instances", new JPanel(), initialized::incrementAndGet);
            assertEquals(0, tabs.getSelectedIndex(), "Swing selects the first tab without opening its action");
            navigator.selectAction(other);
            navigator.publishResults(load, tabs, 0, false);
            assertEquals(0, initialized.get(), "publishing a background result does not render it");

            select(navigator.tree(), child(child(selectedParent(navigator.tree()), "Load instances"), "Instances"));
            assertEquals(1, initialized.get());
            navigator.selectAction(other);
            navigator.selectAction(load);
            assertEquals(1, initialized.get(), "returning to the action reuses its rendered content");
        });
    }

    @Test void aConfiguredActionCollectionShowsEveryChildAndCanBeRebuilt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkbenchActionResultNavigator navigator =
                    new WorkbenchActionResultNavigator();
            var source = navigator.addSource("wikipedia", "Wikipedia", null);
            var recipes = navigator.addActionGroup(source, "configured-evidence",
                    "Configured field evidence", new JPanel(), null);
            navigator.addAction(recipes, "table", "Table rows: List of names",
                    new JPanel(), null);
            navigator.addAction(recipes, "infobox", "Infobox row: Members",
                    new JPanel(), null);

            DefaultMutableTreeNode root = (DefaultMutableTreeNode)
                    navigator.tree().getModel().getRoot();
            DefaultMutableTreeNode sourceNode = (DefaultMutableTreeNode) root.getChildAt(0);
            DefaultMutableTreeNode group = child(sourceNode, "Configured field evidence");
            assertEquals(List.of("Table rows: List of names", "Infobox row: Members"),
                    children(group));

            navigator.clearActions(recipes);
            assertEquals(0, group.getChildCount());
        });
    }

    private static DefaultMutableTreeNode selectedParent(JTree tree) {
        return (DefaultMutableTreeNode) selected(tree).getParent();
    }

    private static DefaultMutableTreeNode selected(JTree tree) {
        return (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
    }

    private static List<String> children(DefaultMutableTreeNode parent) {
        return java.util.stream.IntStream.range(0, parent.getChildCount())
                .mapToObj(parent::getChildAt).map(Object::toString).toList();
    }

    private static DefaultMutableTreeNode child(
            DefaultMutableTreeNode parent, String label) {
        return java.util.stream.IntStream.range(0, parent.getChildCount())
                .mapToObj(index -> (DefaultMutableTreeNode) parent.getChildAt(index))
                .filter(node -> label.equals(node.toString())).findFirst().orElseThrow();
    }

    private static void select(JTree tree, DefaultMutableTreeNode node) {
        tree.setSelectionPath(new TreePath(node.getPath()));
    }

    private static <T> T named(java.awt.Container root, String name, Class<T> type) {
        if (type.isInstance(root) && name.equals(root.getName())) {
            return type.cast(root);
        }
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) {
                return type.cast(child);
            }
            if (child instanceof java.awt.Container nested) {
                T found = named(nested, name, type);
                if (found != null) return found;
            }
        }
        throw new AssertionError("No " + type.getSimpleName() + " named " + name);
    }

    private static <T> T find(java.awt.Container root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof java.awt.Container nested) {
                T found = find(nested, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
