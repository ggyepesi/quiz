package wikidata.explore.tree;

import aux.CachedImage;
import quiz.ui.ImagePane;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.net.URI;
import java.util.List;
import java.util.Map;

public class WikidataObjectTreePanel extends JPanel {

    private final DefaultMutableTreeNode root =
            new DefaultMutableTreeNode("Node instances / Results");

    private final DefaultTreeModel model =
            new DefaultTreeModel(root);

    private final JTree tree =
            new JTree(model);

    public WikidataObjectTreePanel() {
        super(new BorderLayout());
        tree.setRootVisible(true);
        tree.setRowHeight(24);
        tree.setCellRenderer(new WikidataMediaTreeRenderer());
        tree.setToolTipText(
                "Double-click an entity to open Wikidata, "
                + "or an image field to show image.");
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelected();
                }
            }
        });
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    public void setObjects(List<WikidataDynamicObject> objects) {
        root.removeAllChildren();

        if (objects != null) {
            for (WikidataDynamicObject object : objects) {
                root.add(toTreeNode(object));
            }
        }

        model.reload();
        expandTopLevelOnly();
    }

    private DefaultMutableTreeNode toTreeNode(WikidataDynamicObject object) {
        DefaultMutableTreeNode node =
                new DefaultMutableTreeNode(object);

        for (Map.Entry<String, Object> e : object.dynamicFields().entrySet()) {
            String key = e.getKey();

            if ("qid".equals(key) || "wikidataUrl".equals(key)) {
                continue;
            }

            Object value = e.getValue();

            DefaultMutableTreeNode fieldNode =
                    new DefaultMutableTreeNode(key);

            if (value instanceof List<?> list) {
                for (Object child : list) {
                    if (child instanceof WikidataDynamicObject wdo) {
                        fieldNode.add(toTreeNode(wdo));
                    } else {
                        fieldNode.add(new DefaultMutableTreeNode(child));
                    }
                }
            } else if (value instanceof WikidataDynamicObject wdo) {
                fieldNode.add(toTreeNode(wdo));
            } else {
                fieldNode.add(new DefaultMutableTreeNode(value));
            }

            node.add(fieldNode);
        }

        return node;
    }

    /**
     * Expands only the root and the top-level result nodes (one level deep).
     * Field children and image leaves are left collapsed so the renderer
     * doesn't auto-open media nodes.
     */
    private void expandTopLevelOnly() {
        // Expand the root node itself
        tree.expandPath(new TreePath(root.getPath()));

        // Expand each top-level result node, but not their children
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode child =
                    (DefaultMutableTreeNode) root.getChildAt(i);

            TreePath path = new TreePath(child.getPath());
            TreeExpansionUtils.revealNodePathOnly(tree, path);
        }
    }

    private void openSelected() {
        Object treeObj = tree.getLastSelectedPathComponent();

        if (!(treeObj instanceof DefaultMutableTreeNode treeNode)) {
            return;
        }

        Object selected = treeNode.getUserObject();

        if (selected instanceof WikidataDynamicObject object) {
            openUrl(object.wikidataUrl());
            return;
        }

        if (selected instanceof WikidataMediaValue media) {
            showImage(media);
        }
    }

    private void showImage(WikidataMediaValue media) {
        try {
            CachedImage img =
                    new CachedImage(
                            media.label(),
                            media.url(),
                            media.svg());

            ImagePane pane =
                    new ImagePane(media.label(), null, img, true);

            JFrame frame = new JFrame(media.label());
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(new JScrollPane(pane));
            frame.pack();
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void openUrl(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
