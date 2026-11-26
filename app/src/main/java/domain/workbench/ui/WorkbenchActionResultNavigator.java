package domain.workbench.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
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
 * with the host workbench. Tree and detail behavior belongs to the shared
 * {@link TreeAndDetailPanel}.</p>
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
        private final Runnable reveal;

        private ActionGroup(Entry entry, Runnable reveal) {
            this.entry = entry;
            this.reveal = reveal;
        }

        public NodeId id() { return entry.id; }
    }

    private record Entry(NodeId id, Role role, String label) { }

    private record ResultTarget(Action action, JTabbedPane tabs, int tabIndex) { }

    private final TreeAndDetailPanel<NodeId> shell =
            new TreeAndDetailPanel<>("workbench.action-result", "Actions and results");
    private final Map<NodeId, Entry> entries = new LinkedHashMap<>();
    private final Map<NodeId, Action> actions = new LinkedHashMap<>();
    private final Map<NodeId, ActionGroup> actionGroups = new LinkedHashMap<>();
    private final Map<NodeId, ResultTarget> results = new LinkedHashMap<>();
    private final IdentityHashMap<JTabbedPane, Action> tabOwners = new IdentityHashMap<>();
    private final IdentityHashMap<JTabbedPane, javax.swing.event.ChangeListener> tabListeners =
            new IdentityHashMap<>();
    private java.util.function.Consumer<Selection> selectionListener = ignored -> { };
    private boolean synchronizing;

    public WorkbenchActionResultNavigator() {
        super(new BorderLayout());
        setName("workbench.action-result.navigator");
        shell.setSelectionListener(this::revealSelected);
        add(shell, BorderLayout.CENTER);
    }

    public Source addSource(String id, String label, JComponent overview) {
        NodeId key = NodeId.source(id);
        requireNew(key);
        Entry entry = new Entry(key, Role.SOURCE, required(label, "Source label"));
        JComponent detail = overview == null ? sourcePrompt(entry.label) : overview;
        shell.addRoot(key, entry.label, detail);
        entries.put(key, entry);
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
        shell.addChild(source.id(), key, entry.label, detail);
        entries.put(key, entry);
        actions.put(key, action);
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
        ActionGroup group = new ActionGroup(entry,
                reveal == null ? () -> { } : reveal);
        shell.addChild(source.id(), key, entry.label, detail);
        entries.put(key, entry);
        actionGroups.put(key, group);
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
        shell.addChild(group.id(), key, entry.label, detail);
        entries.put(key, entry);
        actions.put(key, action);
        return action;
    }

    /** Removes the visible children and their results; the group itself remains. */
    public void clearActions(ActionGroup group) {
        if (group == null) return;
        shell.removeChildren(group.id()).forEach(this::forget);
    }

    /** Replace one action's result children from the exact tabs its operation produced. */
    public void publishResults(Action action, JTabbedPane tabs, int primaryIndex) {
        publishResults(action, tabs, primaryIndex, true);
    }

    /** A background result may be published without taking the user's current selection. */
    public void publishResults(Action action, JTabbedPane tabs, int primaryIndex, boolean reveal) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(tabs, "tabs");
        clearResults(action);
        Action previous = tabOwners.get(tabs);
        if (previous != null && previous != action) clearResults(previous);
        tabOwners.put(tabs, action);
        installTabListener(tabs);

        for (int index = 0; index < tabs.getTabCount(); index++) {
            String title = tabs.getTitleAt(index);
            String resultId = resultId(index, title);
            NodeId key = NodeId.result(action.id().source(), action.id().action(), resultId);
            Entry entry = new Entry(key, Role.RESULT, title);
            shell.addChild(action.id(), key, entry.label, action.detail);
            entries.put(key, entry);
            results.put(key, new ResultTarget(action, tabs, index));
        }
        shell.expand(action.id());
        if (!reveal) return;
        if (tabs.getTabCount() > 0) {
            int selected = Math.max(0, Math.min(primaryIndex, tabs.getTabCount() - 1));
            selectResult(action, selected);
        } else {
            select(action.id());
        }
    }

    public void clearResults(Action action) {
        if (action == null) return;
        shell.removeChildren(action.id()).forEach(this::forget);
        detachTabs(action);
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

    public JTree tree() { return shell.tree(); }

    /** Keep the datasource/action inventory visible without making a selection or
     * executing anything. Result children remain under their producing action. */
    public void expandSources() {
        entries.values().stream().filter(entry -> entry.role == Role.SOURCE)
                .map(Entry::id).forEach(shell::expand);
    }

    private void revealSelected(TreeAndDetailPanel.Selection<NodeId> selected) {
        Entry entry = entries.get(selected.id());
        if (entry == null) return;
        if (entry.role == Role.ACTION_GROUP) {
            ActionGroup group = actionGroups.get(entry.id);
            if (group != null) group.reveal.run();
        } else if (entry.role == Role.ACTION) {
            Action action = actions.get(entry.id);
            reveal(action);
            tabOwners.forEach((tabs, owner) -> {
                if (owner == action && tabs instanceof WorkbenchResultViews views) {
                    views.revealTab(views.getSelectedIndex());
                }
            });
        } else {
            ResultTarget result = results.get(entry.id);
            if (result != null) {
                reveal(result.action);
                synchronizing = true;
                try {
                    if (result.tabs instanceof WorkbenchResultViews views) {
                        views.revealTab(result.tabIndex);
                    } else {
                        result.tabs.setSelectedIndex(result.tabIndex);
                    }
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
    }

    private void installTabListener(JTabbedPane tabs) {
        javax.swing.event.ChangeListener listener = event -> {
            if (synchronizing) return;
            Action owner = tabOwners.get(tabs);
            if (owner != null && tabs.getSelectedIndex() >= 0) {
                selectResult(owner, tabs.getSelectedIndex());
            }
        };
        tabListeners.put(tabs, listener);
        tabs.addChangeListener(listener);
    }

    private void detachTabs(Action action) {
        tabOwners.entrySet().removeIf(entry -> {
            if (entry.getValue() != action) return false;
            JTabbedPane tabs = entry.getKey();
            tabs.removeChangeListener(tabListeners.remove(tabs));
            return true;
        });
    }

    private void selectResult(Action action, int tabIndex) {
        results.entrySet().stream()
                .filter(entry -> entry.getValue().action == action
                        && entry.getValue().tabIndex == tabIndex)
                .map(Map.Entry::getKey).findFirst().ifPresent(this::select);
    }

    private void select(NodeId key) {
        shell.select(key);
    }

    private void requireNew(NodeId key) {
        if (entries.containsKey(key)) {
            throw new IllegalArgumentException("Duplicate workbench node " + key);
        }
    }

    private void forget(NodeId id) {
        entries.remove(id);
        Action action = actions.remove(id);
        if (action != null) {
            results.entrySet().removeIf(entry -> entry.getValue().action == action);
            detachTabs(action);
        }
        actionGroups.remove(id);
        results.remove(id);
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
