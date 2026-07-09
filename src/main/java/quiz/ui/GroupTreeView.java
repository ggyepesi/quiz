package quiz.ui;

import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.ui.viewconfig.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Renders a faceted {@link QuizableGroup} tree as a collapsible outline — by ROLE,
 * not by reflecting the group's {@code children}/{@code members} fields (which would
 * show the raw structure, and the member union bubbled onto every ancestor).
 *
 * <p>A node's children are FACET (dimension) nodes whose children are the buckets.
 * With a SINGLE dimension the facet layer is pass-through — the outline is just the
 * buckets (e.g. {@code category ▸ year ▸ members}). With SEVERAL parallel dimensions
 * (independent group-bys) each gets a small header so they stay distinct. A bucket
 * drills into its next dimension, or — at a leaf — shows its members as cards.
 * Members render only at leaves; content builds lazily on first expand.
 *
 * <p>An optional member {@link #setMemberFilter filter} prunes non-matching members
 * (and now-empty buckets), a member {@link #setMemberOrder order} sorts them at the
 * leaves, and a {@link #setCardConfig card config} drives which fields the member
 * cards show — so a browser bar can search / sort / configure the outline.
 */
public final class GroupTreeView extends JPanel {

    private static final int INDENT = 14;

    private final QuizableRenderContext context = new QuizableRenderContext();
    private final QuizableGroup root;

    private QuizablePanelConfig cardConfig;                 // null → per-class all()
    private Predicate<Quizable> filter = q -> true;
    private Comparator<Quizable> order;                     // null → group order

    public GroupTreeView(QuizableGroup root) {
        this(root, null);
    }

    public GroupTreeView(QuizableGroup root, QuizablePanelConfig cardConfig) {
        this.root = root;
        this.cardConfig = cardConfig;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        register(root);
        rebuild();
    }

    /** Which fields the member cards show (references collapse to chips unless
     *  expanded here); null renders every field. Rebuilds. */
    public void setCardConfig(QuizablePanelConfig cardConfig) {
        this.cardConfig = cardConfig;
        rebuild();
    }

    /** Keep only members matching {@code filter} (and non-empty buckets); null keeps
     *  all. Rebuilds. */
    public void setMemberFilter(Predicate<Quizable> filter) {
        this.filter = filter == null ? q -> true : filter;
        rebuild();
    }

    /** Order members within each leaf bucket; null keeps the group's order. Rebuilds. */
    public void setMemberOrder(Comparator<Quizable> order) {
        this.order = order;
        rebuild();
    }

    private void rebuild() {
        removeAll();
        if (root != null) {
            if (root.getChildren().isEmpty()) {
                addMembers(this, root);               // no grouping → just members
            } else {
                renderDimensions(this, root);
            }
        }
        add(Box.createVerticalGlue());
        revalidate();
        repaint();
    }

    /** Pre-register every member so a reference chip resolves/navigates. */
    private void register(QuizableGroup g) {
        if (g == null) {
            return;
        }
        for (Quizable m : g.getMembers()) {
            context.addTopLevel(m);
        }
        for (QuizableGroup c : g.getChildren()) {
            register(c);
        }
    }

    /** A node's children are FACET (dimension) nodes. One dimension is pass-through
     *  (render its buckets directly); several get a header each so they stay apart.
     *  Buckets with no matching member (under the active filter) are skipped. */
    private void renderDimensions(JComponent into, QuizableGroup node) {
        List<QuizableGroup> facets = new ArrayList<>(node.getChildren());
        boolean multi = facets.size() > 1;
        for (QuizableGroup facet : facets) {
            List<QuizableGroup> buckets = new ArrayList<>();
            for (QuizableGroup bucket : facet.getChildren()) {
                if (hasMatch(bucket)) {
                    buckets.add(bucket);
                }
            }
            if (buckets.isEmpty()) {
                continue;
            }
            JComponent host = into;
            if (multi) {
                JLabel header = new JLabel(facet.getDisplayName());
                header.setForeground(new Color(120, 120, 130));
                header.setAlignmentX(LEFT_ALIGNMENT);
                into.add(header);
                JPanel body = new JPanel();
                body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
                body.setOpaque(false);
                body.setBorder(BorderFactory.createEmptyBorder(0, INDENT, 4, 0));
                body.setAlignmentX(LEFT_ALIGNMENT);
                into.add(body);
                host = body;
            }
            for (QuizableGroup bucket : buckets) {
                host.add(bucketNode(bucket));
            }
        }
    }

    private JComponent bucketNode(QuizableGroup bucket) {
        boolean leaf = bucket.getChildren().isEmpty();
        int count = leaf ? matchingMembers(bucket).size() : matchingSubBuckets(bucket);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(0, INDENT, 4, 0));
        content.setVisible(false);
        content.setAlignmentX(LEFT_ALIGNMENT);
        boolean[] built = {false};

        String label = bucket.getDisplayName() + "   (" + count + ")";
        JButton header = new JButton("▸  " + label);
        style(header);
        header.addActionListener(e -> {
            boolean show = !content.isVisible();
            if (show && !built[0]) {
                if (leaf) {
                    addMembers(content, bucket);
                } else {
                    renderDimensions(content, bucket);
                }
                built[0] = true;
            }
            content.setVisible(show);
            header.setText((show ? "▾  " : "▸  ") + label);
            revalidate();
            repaint();
        });

        JPanel node = new JPanel();
        node.setLayout(new BoxLayout(node, BoxLayout.Y_AXIS));
        node.setOpaque(false);
        node.setAlignmentX(LEFT_ALIGNMENT);
        node.add(header);
        node.add(content);
        return node;
    }

    private int matchingSubBuckets(QuizableGroup node) {
        int n = 0;
        for (QuizableGroup facet : node.getChildren()) {
            for (QuizableGroup bucket : facet.getChildren()) {
                if (hasMatch(bucket)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** The bucket's members (subtree union) passing the active filter, ordered. */
    private List<Quizable> matchingMembers(QuizableGroup bucket) {
        List<Quizable> out = new ArrayList<>();
        for (Quizable m : bucket.getMembers()) {
            if (m != null && filter.test(m)) {
                out.add(m);
            }
        }
        if (order != null) {
            out.sort(order);
        }
        return out;
    }

    private boolean hasMatch(QuizableGroup bucket) {
        for (Quizable m : bucket.getMembers()) {
            if (m != null && filter.test(m)) {
                return true;
            }
        }
        return false;
    }

    private void addMembers(JComponent into, QuizableGroup bucket) {
        for (Quizable m : matchingMembers(bucket)) {
            QuizablePanelConfig cfg = cardConfig != null
                    ? cardConfig : QuizablePanelConfig.all(m.getClass());
            QuizablePanel card = new QuizablePanel(m, cfg, context, false);
            card.setAlignmentX(LEFT_ALIGNMENT);
            into.add(card);
        }
    }

    private static void style(JButton b) {
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setAlignmentX(LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height));
    }
}
