package wikidata.explore.ui;

import aux.CachedImage;
import wikidata.WikidataTripleSample;
import wikidata.explore.CommonsMedia;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public class GroupedTripleTree extends JPanel {

    private final JTree tree = new JTree();
    private final DefaultMutableTreeNode root =
            new DefaultMutableTreeNode("Relations");

    private final JTextField findField = new JTextField(20);
    private final JButton prevButton = new JButton("Previous");
    private final JButton nextButton = new JButton("Next");
    private final JLabel hitLabel = new JLabel(" ");

    private final List<TreePath> hits = new ArrayList<>();
    private int hitIndex = -1;

    private Consumer<WikidataTripleSample> relationSelectedHandler = s -> {};
    private Consumer<WikidataTripleSample> valueDoubleClickedHandler = s -> {};

    public GroupedTripleTree() {
        super(new BorderLayout());

        tree.setModel(new DefaultTreeModel(root));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(36);
        tree.setCellRenderer(new TripleTreeRenderer());

        tree.addTreeSelectionListener(e -> {
            Object selected = tree.getLastSelectedPathComponent();

            if (!(selected instanceof DefaultMutableTreeNode node)) {
                return;
            }

            Object nodeObj = node.getUserObject();

            // Only property/group nodes select the rule relation.
            if (nodeObj instanceof RelationNode rn) {
                relationSelectedHandler.accept(rn.sample());
            }
        });

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() < 2) {
                    return;
                }

                TreePath path = tree.getPathForLocation(e.getX(), e.getY());

                if (path == null) {
                    return;
                }

                Object obj =
                        ((DefaultMutableTreeNode) path.getLastPathComponent())
                                .getUserObject();

                if (obj instanceof ValueNode vn) {
                    WikidataTripleSample sample = vn.sample();

                    if (sample.media()) {
                        showMedia(sample);
                        return;
                    }

                    valueDoubleClickedHandler.accept(sample);
                }
            }
        });

        add(buildFindPanel(), BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    public void setRelationSelectedHandler(
            Consumer<WikidataTripleSample> handler) {
        this.relationSelectedHandler = handler == null ? s -> {} : handler;
    }

    public void setValueDoubleClickedHandler(
            Consumer<WikidataTripleSample> handler) {
        this.valueDoubleClickedHandler = handler == null ? s -> {} : handler;
    }

    public void setTriples(List<WikidataTripleSample> triples) {
        root.removeAllChildren();
        hits.clear();
        hitIndex = -1;
        hitLabel.setText(" ");

        Map<String, List<WikidataTripleSample>> byProperty = new TreeMap<>();

        for (WikidataTripleSample s : triples) {
            String propertyLabel = safe(s.propertyLabel(), s.propertyPid());
            String key = propertyLabel + " (" + safe(s.propertyPid(), "?") + ")";
            byProperty.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        for (Map.Entry<String, List<WikidataTripleSample>> e : byProperty.entrySet()) {
            List<WikidataTripleSample> rows = e.getValue();

            rows.sort(Comparator.comparing(
                    WikidataTripleSample::valueLabel,
                    Comparator.nullsLast(String::compareToIgnoreCase)));

            WikidataTripleSample representative = rows.get(0);

            DefaultMutableTreeNode propertyNode =
                    new DefaultMutableTreeNode(
                            new RelationNode(e.getKey(), rows.size(), representative));

            for (WikidataTripleSample row : rows) {
                propertyNode.add(new DefaultMutableTreeNode(new ValueNode(row)));
            }

            root.add(propertyNode);
        }

        ((DefaultTreeModel) tree.getModel()).reload();

        // Keep the response tree compact. Find expands matching paths.
        for (int i = tree.getRowCount() - 1; i >= 0; i--) {
            tree.collapseRow(i);
        }

        if (!findField.getText().trim().isBlank()) {
            rebuildHits();
        }
    }

    private JPanel buildFindPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton findButton = new JButton("Find");
        JButton clearButton = new JButton("Clear");

        findButton.addActionListener(e -> rebuildHits());
        findField.addActionListener(e -> rebuildHits());

        prevButton.addActionListener(e -> gotoHit(-1));
        nextButton.addActionListener(e -> gotoHit(1));

        clearButton.addActionListener(e -> {
            findField.setText("");
            hits.clear();
            hitIndex = -1;
            hitLabel.setText(" ");
            tree.clearSelection();
            tree.repaint();
        });

        p.add(new JLabel("Find:"));
        p.add(findField);
        p.add(findButton);
        p.add(prevButton);
        p.add(nextButton);
        p.add(clearButton);
        p.add(hitLabel);

        return p;
    }

    private void rebuildHits() {
        hits.clear();
        hitIndex = -1;

        String text = findField.getText().trim().toLowerCase();

        if (text.isBlank()) {
            hitLabel.setText(" ");
            tree.repaint();
            return;
        }

        collectHits(new TreePath(root), text);

        if (hits.isEmpty()) {
            hitLabel.setText("0 hits");
            tree.clearSelection();
            tree.repaint();
            return;
        }

        hitIndex = 0;
        showCurrentHit();
    }

    private void collectHits(TreePath path, String text) {
        Object last = path.getLastPathComponent();

        if (!(last instanceof DefaultMutableTreeNode node)) {
            return;
        }

        if (node != root) {
            String s = String.valueOf(node.getUserObject()).toLowerCase();

            if (s.contains(text)) {
                hits.add(path);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectHits(path.pathByAddingChild(node.getChildAt(i)), text);
        }
    }

    private void gotoHit(int delta) {
        if (hits.isEmpty()) {
            rebuildHits();
        }

        if (hits.isEmpty()) {
            return;
        }

        hitIndex = (hitIndex + delta + hits.size()) % hits.size();
        showCurrentHit();
    }

    private void showCurrentHit() {
        if (hitIndex < 0 || hitIndex >= hits.size()) {
            return;
        }

        TreePath path = hits.get(hitIndex);

        TreePath parent = path.getParentPath();
        if (parent != null) {
            tree.expandPath(parent);
        }

        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);

        hitLabel.setText((hitIndex + 1) + " / " + hits.size());
        tree.repaint();
    }

    private void showMedia(WikidataTripleSample sample) {
        try {
            String url = CommonsMedia.filePathUrl(sample.mediaUrl());

            CachedImage img =
                    new CachedImage(
                            sample.valueLabel(),
                            url,
                            CommonsMedia.isSvg(sample.valueLabel()));

            JFrame f = new JFrame(safe(sample.valueLabel(), url));
            f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            JLabel label = new JLabel(new ImageIcon(img.getFullImage()));
            label.setHorizontalAlignment(SwingConstants.CENTER);

            f.add(new JScrollPane(label));

            f.setSize(900, 700);
            f.setLocationRelativeTo(this);
            f.setVisible(true);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot open media:\n"
                            + sample.mediaUrl()
                            + "\n\n"
                            + e.getMessage());
        }
    }

    private static String safe(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private record RelationNode(
            String label,
            int count,
            WikidataTripleSample sample) {

        @Override
        public String toString() {
            return label + " — " + count + " value(s)";
        }
    }

    private record ValueNode(WikidataTripleSample sample) {

        @Override
        public String toString() {
            String label = sample.valueLabel();

            if (label == null || label.isBlank()) {
                label = sample.mediaUrl();
            }

            if (label == null || label.isBlank()) {
                label = sample.valueQid();
            }

            if (sample.media()) {
                return "[image] " + label;
            }

            String qid = sample.valueQid();

            return label + (qid == null || qid.isBlank()
                    ? ""
                    : " (" + qid + ")");
        }
    }

    private static class TripleTreeRenderer extends DefaultTreeCellRenderer {

        private static final Icon MEDIA_ICON = createMediaIcon();

        @Override
        public Component getTreeCellRendererComponent(
                JTree tree,
                Object value,
                boolean selected,
                boolean expanded,
                boolean leaf,
                int row,
                boolean hasFocus) {

            super.getTreeCellRendererComponent(
                    tree,
                    value,
                    selected,
                    expanded,
                    leaf,
                    row,
                    hasFocus);

            setOpenIcon(null);
            setClosedIcon(null);
            setLeafIcon(null);

            if (value instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof ValueNode vn
                    && vn.sample().media()) {

                setIcon(MEDIA_ICON);
            } else {
                if (leaf) {
                    setIcon(getDefaultLeafIcon());
                } else if (expanded) {
                    setIcon(getDefaultOpenIcon());
                } else {
                    setIcon(getDefaultClosedIcon());
                }
            }

            return this;
        }

        private static Icon createMediaIcon() {
            // 1. Create a transparent image to draw on
            BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = img.createGraphics();

            // 2. Set rendering hints for smooth text/emoji rendering
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // 3. Set the font size and draw the 🐉 emoji
            g2d.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 28));
            g2d.drawString("🐉", 0, 26); // Adjust X and Y to center the emoji
            g2d.dispose();

            return new ImageIcon(img);
        }
    }
}