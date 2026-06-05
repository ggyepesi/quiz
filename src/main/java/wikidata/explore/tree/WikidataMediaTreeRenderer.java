package wikidata.explore.tree;

import aux.CachedImage;

import javax.swing.*;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;

/**
 * Tree renderer that shows thumbnails for WikidataMediaValue nodes.
 */
public class WikidataMediaTreeRenderer extends DefaultTreeCellRenderer {

    private static final int THUMB_W = 32;
    private static final int THUMB_H = 24;

    @Override
    public Component getTreeCellRendererComponent(
            JTree tree,
            Object value,
            boolean selected,
            boolean expanded,
            boolean leaf,
            int row,
            boolean hasFocus) {

        JLabel label =
                (JLabel) super.getTreeCellRendererComponent(
                        tree,
                        value,
                        selected,
                        expanded,
                        leaf,
                        row,
                        hasFocus);

        Object userObject =
                TreeNodeUserObjects.userObject(value);

        if (!(userObject instanceof WikidataMediaValue media)) {
            return label;
        }

        String url = media.url();

        if (url == null || url.isBlank()) {
            return label;
        }

        try {
            CachedImage img =
                    new CachedImage(
                            media.label(),
                            url,
                            media.svg());

            Image scaled =
                    img.getFullImage()
                       .getScaledInstance(
                               THUMB_W,
                               THUMB_H,
                               Image.SCALE_SMOOTH);

            label.setIcon(new ImageIcon(scaled));
        } catch (Exception ignored) {
            // Keep normal icon if image loading fails.
        }

        return label;
    }
}
