package quiz.ui;

import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.ui.viewconfig.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.function.Function;

/**
 * Hierarchical grouped view with lazily-built, virtualized member cards.
 *
 * Group branches are ordinary persistent Swing panels with expand/collapse.
 * Each leaf bucket owns a VirtualizedCardList, created only when that bucket
 * is first expanded.
 */
public final class GroupTreeVirtualView extends JPanel
        implements ConfigurableVirtualizedQuizableContainer {

    private static final int INDENT = 14;
    private static final int DEFAULT_LEAF_HEIGHT = 420;

    private final QuizableGroup root;
    private final QuizableRenderContext renderContext =
            new QuizableRenderContext();

    private final List<Quizable> allItems = new ArrayList<>();

    private final Map<Quizable, List<LeafView>> leavesByItem =
            new IdentityHashMap<>();

    private final List<LeafView> leaves = new ArrayList<>();

    private QuizablePanelConfig cardConfig;
    private Quizable lastNavigated;

    public GroupTreeVirtualView(
            QuizableGroup root,
            QuizablePanelConfig cardConfig) {

        this.root = root;
        this.cardConfig = cardConfig;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);

        collectUniqueMembers(root, allItems);
        registerTopLevelMembers();
        rebuild();
    }

    @Override
    public List<Quizable> items() {
        return Collections.unmodifiableList(allItems);
    }

    @Override
    public Quizable topVisibleItem() {
        if (lastNavigated != null) {
            return lastNavigated;
        }

        for (LeafView leaf : leaves) {
            if (leaf.isShowing() && leaf.list != null) {
                Quizable q = leaf.list.topVisibleItem();
                if (q != null) {
                    return q;
                }
            }
        }

        return allItems.isEmpty() ? null : allItems.getFirst();
    }

    @Override
    public JComponent navigateToTop(Quizable item) {
        if (item == null) {
            return null;
        }

        List<LeafView> locations = leavesByItem.get(item);
        if (locations == null || locations.isEmpty()) {
            return null;
        }

        LeafView leaf = locations.getFirst();

        leaf.expandAncestors();
        leaf.ensureBuilt();

        // First reveal the leaf itself in the outer grouped scroll pane.
        leaf.scrollRectToVisible(new Rectangle(
                0, 0,
                Math.max(1, leaf.getWidth()),
                Math.max(1, leaf.getHeight())
        ));

        JComponent card = leaf.list.navigateToTop(item);
        lastNavigated = item;

        return card;
    }

    /**
     * Reorders members inside every leaf without changing group structure.
     */
    @Override
    public void setItems(List<Quizable> orderedItems) {
        Map<Quizable, Integer> rank = new IdentityHashMap<>();

        if (orderedItems != null) {
            for (int i = 0; i < orderedItems.size(); i++) {
                rank.put(orderedItems.get(i), i);
            }

            allItems.clear();
            allItems.addAll(orderedItems);
        }

        Comparator<Quizable> comparator =
                Comparator.comparingInt(q ->
                                                rank.getOrDefault(q, Integer.MAX_VALUE));

        for (LeafView leaf : leaves) {
            leaf.setOrder(comparator);
        }
    }

    @Override
    public void setCardConfig(QuizablePanelConfig config) {
        this.cardConfig = config;

        for (LeafView leaf : leaves) {
            leaf.rebuildCards();
        }

        revalidate();
        repaint();
    }

    private void rebuild() {
        removeAll();
        leaves.clear();
        leavesByItem.clear();

        if (root != null) {
            if (root.getChildren().isEmpty()) {
                addLeaf(this, null, root.getMembers());
            } else {
                renderDimensions(this, null, root);
            }
        }

        add(Box.createVerticalGlue());
        revalidate();
        repaint();
    }

    /**
     * Children of a group node are facet/dimension nodes. A single facet is
     * passed through; parallel facets receive their own collapsible branch.
     */
    private void renderDimensions(
            JComponent into,
            Branch parent,
            QuizableGroup node) {

        List<QuizableGroup> facets =
                new ArrayList<>(node.getChildren());

        boolean severalDimensions = facets.size() > 1;

        for (QuizableGroup facet : facets) {
            if (facet == null || facet.getChildren().isEmpty()) {
                continue;
            }

            JComponent host = into;
            Branch facetBranch = parent;

            if (severalDimensions) {
                facetBranch = new Branch(
                        parent,
                        facet.getDisplayName(),
                        false
                );

                into.add(facetBranch);
                host = facetBranch.content;
            }

            for (QuizableGroup bucket : facet.getChildren()) {
                if (bucket == null || bucket.getMembers().isEmpty()) {
                    continue;
                }

                addBucket(host, facetBranch, bucket);
            }
        }
    }

    private void addBucket(
            JComponent into,
            Branch parent,
            QuizableGroup bucket) {

        int members = identityDistinct(bucket.getMembers()).size();
        int children = childNodeCount(bucket);

        String label = bucket.getDisplayName()
                + (children > 0
                        ? "  (" + children + " groups · " + members + " members)"
                        : "  (" + members + " members)");

        Branch branch = new Branch(parent, label, false);

        into.add(branch);

        branch.builder = () -> {
            if (bucket.getChildren().isEmpty()) {
                addLeaf(branch.content, branch, bucket.getMembers());
            } else {
                renderDimensions(branch.content, branch, bucket);

                /*
                 * Members with no value for the next grouping level may not occur
                 * in a child bucket. Preserve them as a direct leaf.
                 */
                List<Quizable> childUnion = identityUnion(bucket.getChildren());
                List<Quizable> orphans = new ArrayList<>();

                for (Quizable q : bucket.getMembers()) {
                    if (q != null && !containsIdentity(childUnion, q)) {
                        orphans.add(q);
                    }
                }

                if (!orphans.isEmpty()) {
                    addLeaf(branch.content, branch, orphans);
                }
            }
        };
    }

    private void addLeaf(
            JComponent into,
            Branch parent,
            Collection<? extends Quizable> members) {

        List<Quizable> unique = identityDistinct(members);

        if (unique.isEmpty()) {
            return;
        }

        LeafView leaf = new LeafView(parent, unique);
        leaves.add(leaf);

        for (Quizable q : unique) {
            leavesByItem
                    .computeIfAbsent(q, ignored -> new ArrayList<>())
                    .add(leaf);
        }

        into.add(leaf);

        /*
         * The branch itself is already being expanded and lazily built.
         * Build the VirtualizedCardList now; the individual QuizablePanels
         * remain virtualized and are still created only for visible rows.
         */
        leaf.ensureBuilt();
    }

    private QuizablePanel createCard(Quizable q) {
        QuizablePanelConfig cfg = configFor(q);

        QuizablePanel panel =
                new QuizablePanel(q, cfg, renderContext, false);

        renderContext.registerTopLevel(q, panel);
        return panel;
    }

    private QuizablePanelConfig configFor(Quizable q) {
        if (cardConfig == null) {
            return QuizablePanelConfig.all(q.getClass());
        }

        QuizablePanelConfig cfg = cardConfig.copy();

        if (cfg.getCls() == null) {
            @SuppressWarnings("unchecked")
            Class<? extends Quizable> cls =
                    (Class<? extends Quizable>) q.getClass();

            cfg.setCls(cls);
        }

        return cfg;
    }

    private void registerTopLevelMembers() {
        for (Quizable q : allItems) {
            renderContext.addTopLevel(q);
        }
    }

    private final class Branch extends JPanel {
        private final Branch parent;
        private final JButton header = new JButton();
        private final JPanel content = new JPanel();

        private final String label;

        private boolean expanded;
        private boolean built;
        private Runnable builder;

        Branch(Branch parent, String label, boolean initiallyExpanded) {
            this.parent = parent;
            this.label = label;
            this.expanded = initiallyExpanded;

            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);

            header.setHorizontalAlignment(SwingConstants.LEFT);
            header.setBorderPainted(false);
            header.setContentAreaFilled(false);
            header.setFocusPainted(false);
            header.setAlignmentX(LEFT_ALIGNMENT);
            header.setMaximumSize(new Dimension(
                    Integer.MAX_VALUE,
                    header.getPreferredSize().height
            ));
            header.addActionListener(e -> setExpanded(!expanded));

            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setOpaque(false);
            content.setAlignmentX(LEFT_ALIGNMENT);
            content.setBorder(BorderFactory.createEmptyBorder(
                    0, INDENT, 4, 0
                                                             ));

            add(header);
            add(content);

            updateHeader();
            content.setVisible(expanded);
        }

        void setExpanded(boolean expanded) {
            if (expanded && !built) {
                built = true;

                if (builder != null) {
                    builder.run();
                }

                content.revalidate();
                content.doLayout();
            }

            this.expanded = expanded;
            content.setVisible(expanded);
            updateHeader();

            GroupTreeVirtualView.this.revalidate();
            GroupTreeVirtualView.this.repaint();
        }

        void expandPath() {
            if (parent != null) {
                parent.expandPath();
            }
            setExpanded(true);
        }

        private void updateHeader() {
            header.setText((expanded ? "▾ " : "▸ ") + label);
        }
    }

    private final class LeafView extends JPanel {
        private final Branch parent;
        private final List<Quizable> sourceItems;

        private Comparator<Quizable> order;

        private VirtualizedCardList list;
        private JScrollPane scroll;

        LeafView(Branch parent, List<Quizable> sourceItems) {
            this.parent = parent;
            this.sourceItems = new ArrayList<>(sourceItems);

            setLayout(new BorderLayout());
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);

            setMaximumSize(new Dimension(
                    Integer.MAX_VALUE,
                    DEFAULT_LEAF_HEIGHT
            ));
        }

        void ensureBuilt() {
            if (list != null) {
                return;
            }

            list = new VirtualizedCardList(
                    GroupTreeVirtualView.this::createCard
            );

            scroll = new JScrollPane();

            int preferredHeight = Math.clamp(sourceItems.size() * 140L, 180,
                                             DEFAULT_LEAF_HEIGHT);

            Dimension size = new Dimension(500, preferredHeight);

            scroll.setPreferredSize(size);
            scroll.setMinimumSize(new Dimension(200, 120));
            scroll.setMaximumSize(new Dimension(
                    Integer.MAX_VALUE,
                    DEFAULT_LEAF_HEIGHT
            ));

            list.install(scroll);
            list.setItems(orderedItems());

            add(scroll, BorderLayout.CENTER);

            setPreferredSize(size);
            setMinimumSize(new Dimension(200, 120));
            setMaximumSize(new Dimension(
                    Integer.MAX_VALUE,
                    DEFAULT_LEAF_HEIGHT
            ));

            revalidate();
            repaint();
        }

        void expandAncestors() {
            if (parent != null) {
                parent.expandPath();
            }

            ensureBuilt();
        }

        void setOrder(Comparator<Quizable> order) {
            this.order = order;

            if (list != null) {
                list.setItems(orderedItems());
            }
        }

        void rebuildCards() {
            if (list == null) {
                return;
            }

            list.setCardFactory(
                    GroupTreeVirtualView.this::createCard
                               );
            list.setItems(orderedItems());
        }

        private List<Quizable> orderedItems() {
            List<Quizable> out =
                    new ArrayList<>(sourceItems);

            if (order != null) {
                out.sort(order);
            }

            return out;
        }
    }

    /** Number of non-empty sub-buckets under {@code bucket} (across its facet
     *  dimensions) — the child group nodes it renders; 0 for a leaf. */
    private static int childNodeCount(QuizableGroup bucket) {
        int n = 0;
        for (QuizableGroup facet : bucket.getChildren()) {
            for (QuizableGroup sub : facet.getChildren()) {
                if (sub != null && !sub.getMembers().isEmpty()) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void collectUniqueMembers(
            QuizableGroup group,
            List<Quizable> out) {

        if (group == null) {
            return;
        }

        for (Quizable q : group.getMembers()) {
            if (q != null && !containsIdentity(out, q)) {
                out.add(q);
            }
        }

        for (QuizableGroup child : group.getChildren()) {
            collectUniqueMembers(child, out);
        }
    }

    private static List<Quizable> identityUnion(
            Collection<QuizableGroup> groups) {

        List<Quizable> out = new ArrayList<>();

        for (QuizableGroup group : groups) {
            collectUniqueMembers(group, out);
        }

        return out;
    }

    private static List<Quizable> identityDistinct(
            Collection<? extends Quizable> input) {

        Set<Quizable> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());

        List<Quizable> out = new ArrayList<>();

        if (input != null) {
            for (Quizable q : input) {
                if (q != null && seen.add(q)) {
                    out.add(q);
                }
            }
        }

        return out;
    }

    private static boolean containsIdentity(
            Collection<? extends Quizable> values,
            Quizable candidate) {

        for (Quizable q : values) {
            if (q == candidate) {
                return true;
            }
        }

        return false;
    }
}