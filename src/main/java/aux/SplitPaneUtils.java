package aux;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Small helper for JSplitPane polish.
 *
 * Fixes/mitigates:
 * - stuck thick divider drag marker line
 * - delayed repaint after mouse release
 * - inconsistent nested split-pane resizing
 */
public final class SplitPaneUtils {

    private SplitPaneUtils() {
    }

    public static JSplitPane polish(JSplitPane split) {
        if (split == null) {
            return null;
        }

        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        split.setDividerSize(8);

        split.addPropertyChangeListener(
                JSplitPane.DIVIDER_LOCATION_PROPERTY,
                e -> repaintLater(split));

        split.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                repaintLater(split);
            }
        });

        Component divider =
                findDivider(split);

        if (divider != null) {
            divider.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    repaintLater(split);
                }
            });
        }

        return split;
    }

    public static JSplitPane vertical(
            Component top,
            Component bottom,
            double resizeWeight) {

        JSplitPane split =
                new JSplitPane(
                        JSplitPane.VERTICAL_SPLIT,
                        top,
                        bottom);

        split.setResizeWeight(resizeWeight);

        return polish(split);
    }

    public static JSplitPane horizontal(
            Component left,
            Component right,
            double resizeWeight) {

        JSplitPane split =
                new JSplitPane(
                        JSplitPane.HORIZONTAL_SPLIT,
                        left,
                        right);

        split.setResizeWeight(resizeWeight);

        return polish(split);
    }

    public static void repaintLater(Component component) {
        if (component == null) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            component.invalidate();
            component.validate();
            component.repaint();

            Window w =
                    SwingUtilities.getWindowAncestor(component);

            if (w != null) {
                w.repaint();
            }
        });
    }

    private static Component findDivider(JSplitPane split) {
        for (Component c : split.getComponents()) {
            if (c.getClass().getName().contains("Divider")) {
                return c;
            }
        }

        return null;
    }
}
