package domain.workbench.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reusable navigation for workbenches shaped as context/source → explicit action →
 * views of that action's latest result.
 *
 * <p>The navigator never executes an action and never interprets a result. Selecting an
 * action reveals the operation component supplied by its owner. Publishing a result mirrors
 * the supplied tab set as result children; selecting either representation updates the same
 * selected result. Domain selection, execution, persistence and result construction remain
 * with the host workbench.</p>
 */
public final class WorkbenchActionResultNavigator extends JPanel {

    public enum Role { SOURCE, ACTION_GROUP, ACTION, RESULT }

    /** Stable identity independent of labels, counts and action status. */
    public record NodeId(String source, String action, String result) {
        public NodeId {
            source = clean(source);
            action = clean(action);
            result = clean(result);
            if (source.isBlank()) throw new IllegalArgumentException("Source id is required");
        }

        public static NodeId source(String source) {
            return new NodeId(source, "", "");
        }

        public static NodeId action(String source, String action) {
            return new NodeId(source, action, "");
        }

        public static NodeId result(String source, String action, String result) {
            return new NodeId(source, action, result);
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public record Selection(NodeId id, Role role, String label) { }

    public final class Source {
        private final Entry entry;
        private Source(Entry entry) { this.entry = entry; }
        public NodeId id() { return entry.id; }
    }

    public final class Action {
        private final Entry entry;
        private final Runnable reveal;
        private final JComponent detail;

        private Action(Entry entry, JComponent detail, Runnable reveal) {
            this.entry = entry;
            this.detail = detail;
            this.reveal = reveal;
        }

        public NodeId id() { return entry.id; }
    }

    public final class ActionGroup {
        private final Entry entry;
        private final JComponent detail;
        private final Runnable reveal;

        private ActionGroup(Entry entry, JComponent detail, Runnable reveal) {
            this.entry = entry;
            this.detail = detail;
            this.reveal = reveal;
        }

        public NodeId id() { return entry.id; }
    }

    private record Entry(NodeId id, Role role, String label) {
        @Override public String toString() { return label; }
    }

    private record ResultTarget(Action action, JTabbedPane tabs, int tabIndex) { }

    private final DefaultMutableTreeNode hiddenRoot =
            new DefaultMutableTreeNode("Workbench");
    private final DefaultTreeModel model = new DefaultTreeModel(hiddenRoot);
    private final JTree tree = new JTree(model);
    private final JPanel detailHost = new JPanel(new CardLayout());
    private final Map<NodeId, DefaultMutableTreeNode> nodes = new LinkedHashMap<>();
    private final Map<NodeId, Action> actions = new LinkedHashMap<>();
    private final Map<NodeId, ActionGroup> actionGroups = new LinkedHashMap<>();
    private final Map<NodeId, ResultTarget> results = new LinkedHashMap<>();
    private final IdentityHashMap<Component, String> detailCards = new IdentityHashMap<>();
    private final IdentityHashMap<JTabbedPane, Action> tabOwners = new IdentityHashMap<>();
    private final IdentityHashMap<JTabbedPane, Boolean> tabListeners = new IdentityHashMap<>();
    private java.util.function.Consumer<Selection> selectionListener = ignored -> { };
    private int nextCard;
    private boolean synchronizing;

    public WorkbenchActionResultNavigator() {
        super(new BorderLayout());
        setName("workbench.action-result.navigator");
        tree.setName("workbench.action-result.tree");
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(
                javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(event -> revealSelected());

        JScrollPane outline = new JScrollPane(tree);
        outline.setBorder(BorderFactory.createTitledBorder("Actions and results"));
        outline.setMinimumSize(new Dimension(180, 0));
        outline.setPreferredSize(new Dimension(220, 0));
        detailHost.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
        detailHost.setName("workbench.action-result.detail");
        // The operation and its results are the work area. Keep the navigation tree
        // compact and send all additional width to the detail instead of letting two
        // nested split panes squeeze it down to a narrow strip.
        detailHost.setMinimumSize(new Dimension(520, 0));
        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, outline, detailHost);
        split.setName("workbench.action-result.split");
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        split.setResizeWeight(0.0);
        split.setDividerLocation(220);
        add(split, BorderLayout.CENTER);
    }

    public Source addSource(String id, String label, JComponent overview) {
        NodeId key = NodeId.source(id);
        requireNew(key);
        Entry entry = new Entry(key, Role.SOURCE, required(label, "Source label"));
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(entry);
        hiddenRoot.add(node);
        nodes.put(key, node);
        JComponent detail = overview == null ? sourcePrompt(entry.label) : overview;
        ensureDetail(detail);
        node.setUserObject(new SourceEntry(entry, detail));
        model.reload(hiddenRoot);
        return new Source(entry);
    }

    public Action addAction(Source source, String id, String label,
            JComponent detail, Runnable reveal) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(detail, "detail");
        NodeId key = NodeId.action(source.id().source(), required(id, "Action id"));
        requireNew(key);
        Entry entry = new Entry(key, Role.ACTION, required(label, "Action label"));
        Action action = new Action(entry, detail, reveal == null ? () -> { } : reveal);
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(entry);
        nodes.get(source.id()).add(node);
        nodes.put(key, node);
        actions.put(key, action);
        ensureDetail(detail);
        model.reload(nodes.get(source.id()));
        return action;
    }

    /** A visible container whose configured child actions are shown explicitly. */
    public ActionGroup addActionGroup(Source source, String id, String label,
            JComponent detail, Runnable reveal) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(detail, "detail");
        NodeId key = NodeId.action(source.id().source(), required(id, "Action group id"));
        requireNew(key);
        Entry entry = new Entry(key, Role.ACTION_GROUP,
                required(label, "Action group label"));
        ActionGroup group = new ActionGroup(entry, detail,
                reveal == null ? () -> { } : reveal);
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(entry);
        nodes.get(source.id()).add(node);
        nodes.put(key, node);
        actionGroups.put(key, group);
        ensureDetail(detail);
        model.reload(nodes.get(source.id()));
        return group;
    }

    public Action addAction(ActionGroup group, String id, String label,
            JComponent detail, Runnable reveal) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(detail, "detail");
        String childId = group.id().action() + "/" + required(id, "Action id");
        NodeId key = NodeId.action(group.id().source(), childId);
        requireNew(key);
        Entry entry = new Entry(key, Role.ACTION, required(label, "Action label"));
        Action action = new Action(entry, detail, reveal == null ? () -> { } : reveal);
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(entry);
        nodes.get(group.id()).add(node);
        nodes.put(key, node);
        actions.put(key, action);
        ensureDetail(detail);
        model.reload(nodes.get(group.id()));
        return action;
    }

    /** Removes the visible children and their results; the group itself remains. */
    public void clearActions(ActionGroup group) {
        if (group == null) return;
        DefaultMutableTreeNode groupNode = nodes.get(group.id());
        if (groupNode == null) return;
        List<DefaultMutableTreeNode> children = new ArrayList<>();
        for (int i = 0; i < groupNode.getChildCount(); i++) {
            children.add((DefaultMutableTreeNode) groupNode.getChildAt(i));
        }
        for (DefaultMutableTreeNode child : children) removeSubtree(child);
        groupNode.removeAllChildren();
        model.reload(groupNode);
    }

    /** Replace one action's result children from the exact tabs its operation produced. */
    public void publishResults(Action action, JTabbedPane tabs, int primaryIndex) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(tabs, "tabs");
        clearResults(action);
        Action previous = tabOwners.get(tabs);
        if (previous != null && previous != action) clearResults(previous);
        tabOwners.put(tabs, action);
        installTabListener(tabs);

        DefaultMutableTreeNode actionNode = nodes.get(action.id());
        for (int index = 0; index < tabs.getTabCount(); index++) {
            String title = tabs.getTitleAt(index);
            String resultId = resultId(index, title);
            NodeId key = NodeId.result(action.id().source(), action.id().action(), resultId);
            Entry entry = new Entry(key, Role.RESULT, title);
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(entry);
            actionNode.add(node);
            nodes.put(key, node);
            results.put(key, new ResultTarget(action, tabs, index));
        }
        model.reload(actionNode);
        tree.expandPath(new TreePath(actionNode.getPath()));
        if (tabs.getTabCount() > 0) {
            int selected = Math.max(0, Math.min(primaryIndex, tabs.getTabCount() - 1));
            selectResult(action, selected);
        } else {
            select(action.id());
        }
    }

    public void clearResults(Action action) {
        if (action == null) return;
        DefaultMutableTreeNode actionNode = nodes.get(action.id());
        if (actionNode == null) return;
        List<NodeId> removed = new ArrayList<>();
        for (int index = 0; index < actionNode.getChildCount(); index++) {
            Object value = ((DefaultMutableTreeNode) actionNode.getChildAt(index))
                    .getUserObject();
            Entry entry = entry(value);
            if (entry != null) removed.add(entry.id);
        }
        actionNode.removeAllChildren();
        removed.forEach(key -> {
            nodes.remove(key);
            results.remove(key);
        });
        tabOwners.entrySet().removeIf(entry -> entry.getValue() == action);
        model.reload(actionNode);
    }

    public void clearAllResults() {
        List.copyOf(actions.values()).forEach(this::clearResults);
    }

    public void selectSource(Source source) {
        if (source != null) select(source.id());
    }

    public void selectAction(Action action) {
        if (action != null) select(action.id());
    }

    public void setSelectionListener(
            java.util.function.Consumer<Selection> listener) {
        selectionListener = listener == null ? ignored -> { } : listener;
    }

    public JTree tree() { return tree; }

    /** Keep the datasource/action inventory visible without making a selection or
     * executing anything. Result children remain under their producing action. */
    public void expandSources() {
        for (int index = 0; index < hiddenRoot.getChildCount(); index++) {
            DefaultMutableTreeNode source =
                    (DefaultMutableTreeNode) hiddenRoot.getChildAt(index);
            tree.expandPath(new TreePath(source.getPath()));
        }
    }

    private void revealSelected() {
        DefaultMutableTreeNode node = selectedNode();
        Entry entry = node == null ? null : entry(node.getUserObject());
        if (entry == null) return;
        if (entry.role == Role.SOURCE) {
            SourceEntry source = (SourceEntry) node.getUserObject();
            show(source.detail);
        } else if (entry.role == Role.ACTION_GROUP) {
            ActionGroup group = actionGroups.get(entry.id);
            if (group != null) {
                group.reveal.run();
                show(group.detail);
            }
        } else if (entry.role == Role.ACTION) {
            reveal(actions.get(entry.id));
        } else {
            ResultTarget result = results.get(entry.id);
            if (result != null) {
                reveal(result.action);
                synchronizing = true;
                try {
                    result.tabs.setSelectedIndex(result.tabIndex);
                } finally {
                    synchronizing = false;
                }
            }
        }
        selectionListener.accept(new Selection(entry.id, entry.role, entry.label));
    }

    private void reveal(Action action) {
        if (action == null) return;
        action.reveal.run();
        show(action.detail);
    }

    private void show(JComponent detail) {
        String card = detailCards.get(detail);
        if (card != null) ((CardLayout) detailHost.getLayout()).show(detailHost, card);
    }

    private String addDetail(JComponent detail) {
        String card = "detail-" + (++nextCard);
        detailHost.add(detail, card);
        return card;
    }

    private void ensureDetail(JComponent detail) {
        if (!detailCards.containsKey(detail)) {
            detailCards.put(detail, addDetail(detail));
        }
    }

    private void installTabListener(JTabbedPane tabs) {
        if (tabListeners.putIfAbsent(tabs, Boolean.TRUE) != null) return;
        tabs.addChangeListener(event -> {
            if (synchronizing) return;
            Action owner = tabOwners.get(tabs);
            if (owner != null && tabs.getSelectedIndex() >= 0) {
                selectResult(owner, tabs.getSelectedIndex());
            }
        });
    }

    private void selectResult(Action action, int tabIndex) {
        results.entrySet().stream()
                .filter(entry -> entry.getValue().action == action
                        && entry.getValue().tabIndex == tabIndex)
                .map(Map.Entry::getKey).findFirst().ifPresent(this::select);
    }

    private void select(NodeId key) {
        DefaultMutableTreeNode node = nodes.get(key);
        if (node == null) return;
        TreePath path = new TreePath(node.getPath());
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
    }

    private DefaultMutableTreeNode selectedNode() {
        Object value = tree.getLastSelectedPathComponent();
        return value instanceof DefaultMutableTreeNode node ? node : null;
    }

    private void requireNew(NodeId key) {
        if (nodes.containsKey(key)) {
            throw new IllegalArgumentException("Duplicate workbench node " + key);
        }
    }

    private void removeSubtree(DefaultMutableTreeNode node) {
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            removeSubtree((DefaultMutableTreeNode) node.getChildAt(i));
        }
        Entry value = entry(node.getUserObject());
        if (value == null) return;
        nodes.remove(value.id);
        Action action = actions.remove(value.id);
        if (action != null) {
            results.entrySet().removeIf(entry -> entry.getValue().action == action);
            tabOwners.entrySet().removeIf(entry -> entry.getValue() == action);
            detailCards.remove(action.detail);
            detailHost.remove(action.detail);
        }
        actionGroups.remove(value.id);
        results.remove(value.id);
    }

    private static String required(String value, String name) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(name + " is required");
        return clean;
    }

    private static String resultId(int index, String title) {
        return index + "-" + (title == null ? "result" : title.trim()
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", ""));
    }

    private static Entry entry(Object value) {
        if (value instanceof Entry entry) return entry;
        return value instanceof SourceEntry source ? source.entry : null;
    }

    private record SourceEntry(Entry entry, JComponent detail) {
        @Override public String toString() { return entry.label; }
    }

    private static JComponent sourcePrompt(String label) {
        JLabel message = new JLabel(
                "<html><h2>" + escape(label) + "</h2>Choose an action under this source."
                        + " Selecting an action does not run it.</html>",
                SwingConstants.LEFT);
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        panel.add(message, BorderLayout.NORTH);
        return panel;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
