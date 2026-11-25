package domain.workbench.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
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
import java.util.function.Consumer;

/**
 * Neutral tree navigation beside a card of details.
 *
 * <p>The shell owns stable node identity, hierarchy, selection, expansion, detail
 * placement and navigation. Its caller owns what a node means and what selecting it
 * may reveal. Adding, expanding and selecting nodes never executes an operation or
 * changes a model by itself.</p>
 */
public final class TreeAndDetailPanel<K> extends JPanel {

    /** One complete projected branch. Replacing a projection uses stable ids to
     * restore the reader's selection and expanded branches. */
    public record Branch<K>(K id, String caption, JComponent detail,
            List<Branch<K>> children) {
        public Branch {
            Objects.requireNonNull(id, "Node id");
            caption = required(caption, "Node caption");
            Objects.requireNonNull(detail, "Node detail");
            children = children == null ? List.of() : List.copyOf(children);
        }

        public Branch(K id, String caption, JComponent detail) {
            this(id, caption, detail, List.of());
        }
    }

    /** What the shell exposes about one selected node. */
    public record Selection<K>(K id, String caption) { }

    /** The neutral value stored in the Swing tree. */
    public record Item<K>(K id, String caption) {
        public Item {
            Objects.requireNonNull(id, "Node id");
            caption = required(caption, "Node caption");
        }

        @Override public String toString() { return caption; }
    }

    private static final int OUTLINE_WIDTH = 220;
    private static final int DETAIL_MINIMUM_WIDTH = 520;

    private final DefaultMutableTreeNode hiddenRoot =
            new DefaultMutableTreeNode("Tree and detail");
    private final DefaultTreeModel model = new DefaultTreeModel(hiddenRoot);
    private final JTree tree = new JTree(model);
    private final JPanel detailHost = new JPanel(new CardLayout());
    private final JPanel emptyDetail = new JPanel();
    private final JSplitPane split;
    private final Map<K, DefaultMutableTreeNode> nodes = new LinkedHashMap<>();
    private final Map<K, JComponent> nodeDetails = new LinkedHashMap<>();
    private final IdentityHashMap<Component, DetailCard> detailCards =
            new IdentityHashMap<>();
    private Consumer<Selection<K>> selectionListener = ignored -> { };
    private int nextCard;
    private boolean replacing;

    public TreeAndDetailPanel(String componentNamePrefix, String outlineTitle) {
        super(new BorderLayout());
        String prefix = required(componentNamePrefix, "Component name prefix");
        setName(prefix + ".navigator");
        tree.setName(prefix + ".tree");
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(
                javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(event -> revealSelected());

        JScrollPane outline = new JScrollPane(tree);
        outline.setBorder(BorderFactory.createTitledBorder(
                required(outlineTitle, "Outline title")));
        outline.setMinimumSize(new Dimension(180, 0));
        outline.setPreferredSize(new Dimension(OUTLINE_WIDTH, 0));

        detailHost.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
        detailHost.setName(prefix + ".detail");
        detailHost.setMinimumSize(new Dimension(DETAIL_MINIMUM_WIDTH, 0));
        emptyDetail.setName(prefix + ".empty-detail");
        detailHost.add(emptyDetail, "empty");

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, outline, detailHost);
        split.setName(prefix + ".split");
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        split.setResizeWeight(0.0);
        split.setDividerLocation(OUTLINE_WIDTH);
        add(split, BorderLayout.CENTER);
    }

    public void addRoot(K id, String caption, JComponent detail) {
        add(null, id, caption, detail);
    }

    void setDetailHeader(JComponent header) {
        JPanel content = new JPanel(new BorderLayout());
        content.add(header, BorderLayout.NORTH);
        content.add(detailHost, BorderLayout.CENTER);
        split.setRightComponent(content);
    }

    public void addChild(K parentId, K id, String caption, JComponent detail) {
        Objects.requireNonNull(parentId, "Parent id");
        add(parentId, id, caption, detail);
    }

    void updateCaption(K id, String caption) {
        DefaultMutableTreeNode node = nodes.get(id);
        if (node != null && node.getUserObject() instanceof Item<?> item
                && !item.caption().equals(caption)) {
            node.setUserObject(new Item<>(id, caption));
            model.nodeChanged(node);
        }
    }

    /** Replaces the complete visible projection. Stable ids preserve the reader's
     * selection and expanded branches; if the selected id disappears, no unrelated
     * detail remains visible. */
    public void replaceRoots(List<Branch<K>> roots) {
        K selected = selectedId();
        List<K> expanded = expandedIds();
        replacing = true;
        try {
            hiddenRoot.removeAllChildren();
            nodes.clear();
            nodeDetails.clear();
            for (Component detail : List.copyOf(detailCards.keySet())) {
                detailHost.remove(detail);
            }
            detailCards.clear();
            for (Branch<K> root : roots == null ? List.<Branch<K>>of() : roots) {
                append(hiddenRoot, root);
            }
            model.reload(hiddenRoot);
            expanded.stream().filter(nodes::containsKey).forEach(this::expand);
            if (selected != null && nodes.containsKey(selected)) {
                select(selected);
            } else {
                tree.clearSelection();
                showEmptyDetail();
            }
        } finally {
            replacing = false;
        }
        revealDetailOnly();
    }

    private void add(K parentId, K id, String caption, JComponent detail) {
        Objects.requireNonNull(id, "Node id");
        Objects.requireNonNull(detail, "Node detail");
        if (nodes.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate tree-and-detail node " + id);
        }
        DefaultMutableTreeNode parent = parentId == null
                ? hiddenRoot : nodes.get(parentId);
        if (parent == null) {
            throw new IllegalArgumentException("Unknown tree-and-detail parent " + parentId);
        }
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Item<>(id, caption));
        parent.add(node);
        nodes.put(id, node);
        retainDetail(detail);
        nodeDetails.put(id, detail);
        // Report only the insertion: reloading the parent collapsed every expanded node
        // beneath it and cleared the selection, so each rebuild lost the reader's place.
        model.nodesWereInserted(parent, new int[] { parent.getIndex(node) });
    }

    /** Removes every descendant and returns their ids in tree order. */
    public List<K> removeChildren(K parentId) {
        DefaultMutableTreeNode parent = nodes.get(parentId);
        if (parent == null) return List.of();
        List<DefaultMutableTreeNode> children = new ArrayList<>();
        for (int index = 0; index < parent.getChildCount(); index++) {
            children.add((DefaultMutableTreeNode) parent.getChildAt(index));
        }
        K selected = selectedId();
        List<K> removed = new ArrayList<>();
        for (DefaultMutableTreeNode child : children) {
            collectAndRemove(child, removed);
        }
        parent.removeAllChildren();
        if (!children.isEmpty()) {
            int[] indices = java.util.stream.IntStream.range(0, children.size()).toArray();
            model.nodesWereRemoved(parent, indices, children.toArray());
        }
        if (selected != null && removed.contains(selected)) {
            tree.clearSelection();
            showEmptyDetail();
        }
        return List.copyOf(removed);
    }

    public void select(K id) {
        DefaultMutableTreeNode node = nodes.get(id);
        if (node == null) return;
        TreePath path = new TreePath(node.getPath());
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
    }

    public void expand(K id) {
        DefaultMutableTreeNode node = nodes.get(id);
        if (node != null) tree.expandPath(new TreePath(node.getPath()));
    }

    public void setSelectionListener(Consumer<Selection<K>> listener) {
        selectionListener = listener == null ? ignored -> { } : listener;
    }

    public JTree tree() { return tree; }

    public K selectedId() {
        Object selected = tree.getLastSelectedPathComponent();
        if (!(selected instanceof DefaultMutableTreeNode node)
                || !(node.getUserObject() instanceof Item<?> raw)) return null;
        @SuppressWarnings("unchecked") Item<K> item = (Item<K>) raw;
        return item.id();
    }

    public boolean contains(K id) { return nodes.containsKey(id); }

    /** Appearance belongs to the semantic projection, not this neutral shell. */
    public void setCellRenderer(TreeCellRenderer renderer) {
        tree.setCellRenderer(renderer);
    }

    JSplitPane splitPane() { return split; }

    JPanel detailHost() { return detailHost; }

    private void revealSelected() {
        revealDetailOnly();
        if (replacing) return;
        Object selected = tree.getLastSelectedPathComponent();
        if (!(selected instanceof DefaultMutableTreeNode node)
                || !(node.getUserObject() instanceof Item<?> raw)) return;
        @SuppressWarnings("unchecked")
        Item<K> item = (Item<K>) raw;
        selectionListener.accept(new Selection<>(item.id(), item.caption()));
    }

    private void revealDetailOnly() {
        Object selected = tree.getLastSelectedPathComponent();
        if (!(selected instanceof DefaultMutableTreeNode node)
                || !(node.getUserObject() instanceof Item<?> raw)) {
            showEmptyDetail();
            return;
        }
        @SuppressWarnings("unchecked")
        Item<K> item = (Item<K>) raw;
        JComponent detail = nodeDetails.get(item.id());
        DetailCard card = detailCards.get(detail);
        if (card != null) {
            ((CardLayout) detailHost.getLayout()).show(detailHost, card.name());
        } else {
            showEmptyDetail();
        }
    }

    private void append(DefaultMutableTreeNode parent, Branch<K> branch) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(
                new Item<>(branch.id(), branch.caption()));
        parent.add(node);
        nodes.put(branch.id(), node);
        retainDetail(branch.detail());
        nodeDetails.put(branch.id(), branch.detail());
        for (Branch<K> child : branch.children()) append(node, child);
    }

    private List<K> expandedIds() {
        List<K> result = new ArrayList<>();
        for (Map.Entry<K, DefaultMutableTreeNode> entry : nodes.entrySet()) {
            if (tree.isExpanded(new TreePath(entry.getValue().getPath()))) {
                result.add(entry.getKey());
            }
        }
        return List.copyOf(result);
    }

    private void showEmptyDetail() {
        ((CardLayout) detailHost.getLayout()).show(detailHost, "empty");
    }

    private void collectAndRemove(DefaultMutableTreeNode node, List<K> removed) {
        @SuppressWarnings("unchecked")
        Item<K> item = (Item<K>) node.getUserObject();
        removed.add(item.id());
        nodes.remove(item.id());
        JComponent detail = nodeDetails.remove(item.id());
        releaseDetail(detail);
        for (int index = 0; index < node.getChildCount(); index++) {
            collectAndRemove((DefaultMutableTreeNode) node.getChildAt(index), removed);
        }
    }

    private void retainDetail(JComponent detail) {
        DetailCard current = detailCards.get(detail);
        if (current != null) {
            detailCards.put(detail, new DetailCard(current.name(), current.references() + 1));
            return;
        }
        String name = "detail-" + (++nextCard);
        detailHost.add(detail, name);
        detailCards.put(detail, new DetailCard(name, 1));
    }

    private void releaseDetail(JComponent detail) {
        if (detail == null) return;
        DetailCard current = detailCards.get(detail);
        if (current == null) return;
        if (current.references() > 1) {
            detailCards.put(detail, new DetailCard(current.name(), current.references() - 1));
            return;
        }
        detailCards.remove(detail);
        detailHost.remove(detail);
    }

    private static String required(String value, String name) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(name + " is required");
        return clean;
    }

    private record DetailCard(String name, int references) { }
}
