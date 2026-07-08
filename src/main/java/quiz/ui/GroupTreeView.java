package quiz.ui;

import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.ui.viewconfig.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;

/**
 * Renders a faceted {@link QuizableGroup} tree as a collapsible outline — by ROLE,
 * not by reflecting the group's {@code children}/{@code members} fields (which would
 * show the raw structure, and the member union bubbled onto every ancestor).
 *
 * <p>The FACET dimension nodes are pass-through (they just name the grouping), so
 * the outline is only the BUCKET levels — e.g. {@code category ▸ year ▸ members}.
 * A bucket drills into its next dimension's buckets, or, at a leaf, shows its
 * members as cards. Members render only at leaves. Content is built lazily on the
 * first expand.
 */
public final class GroupTreeView extends JPanel {

    private static final int INDENT = 14;

    private final QuizableRenderContext context = new QuizableRenderContext();

    public GroupTreeView(QuizableGroup root) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        register(root);

        if (root == null) {
            return;
        }
        if (root.getChildren().isEmpty()) {
            addMembers(this, root);                // no grouping → just members
        } else {
            for (QuizableGroup bucket : buckets(root)) {
                add(bucketNode(bucket));
            }
        }
        add(Box.createVerticalGlue());
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

    /** The BUCKETs beneath a node, skipping the FACET dimension layer: a node's
     *  children are FACET nodes whose children are the buckets. */
    private static java.util.List<QuizableGroup> buckets(QuizableGroup node) {
        java.util.List<QuizableGroup> out = new java.util.ArrayList<>();
        for (QuizableGroup facet : node.getChildren()) {
            out.addAll(facet.getChildren());
        }
        return out;
    }

    private JComponent bucketNode(QuizableGroup bucket) {
        boolean leaf = bucket.getChildren().isEmpty();
        int count = leaf ? bucket.getMembers().size() : buckets(bucket).size();

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
                    for (QuizableGroup sub : buckets(bucket)) {
                        content.add(bucketNode(sub));
                    }
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

    private void addMembers(JComponent into, QuizableGroup bucket) {
        for (Quizable m : bucket.getMembers()) {
            if (m == null) {
                continue;
            }
            QuizablePanel card = new QuizablePanel(
                    m, QuizablePanelConfig.all(m.getClass()), context, false);
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
