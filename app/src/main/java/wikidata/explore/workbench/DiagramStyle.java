package wikidata.explore.workbench;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;

/**
 * How a painted workbench diagram takes its colours and trims its labels.
 *
 * <p>Text follows the look and feel, because a foreground that ignores it is
 * unreadable on a dark one. The accent stays literal, as it is in the card renderers
 * these diagrams sit beside, and is applied as a TINT so it composites over whatever
 * background is actually beneath it rather than punching a light rectangle through it.
 *
 * <p>This lives here because stating the rule once in a diagram is not the same as
 * being able to follow it: the second diagram re-implemented it and the third got the
 * theme half wrong.
 */
final class DiagramStyle {

    private DiagramStyle() { }

    /** The accent every workbench diagram shares. */
    static final Color ACCENT = new Color(30, 110, 210);

    /** Primary label text. */
    static Color text() {
        return ui("Label.foreground", Color.DARK_GRAY);
    }

    /** Secondary text, arrows and the empty-state message. */
    static Color muted() {
        return ui("Label.disabledForeground", Color.GRAY);
    }

    /** A fill that composites over the background instead of replacing it. */
    static Color tint() {
        return tint(ACCENT);
    }

    /** The same fill for a caller that emphasises with its own colour. */
    static Color tint(Color base) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), 28);
    }

    /** The drawing surface a diagram fills, when it paints one at all. */
    static Color surface() {
        return ui("Panel.background", Color.WHITE);
    }

    /** A border for a box the reader is not being pointed at. */
    static Color quietBorder() {
        return ui("Separator.foreground", ui("Label.disabledForeground", Color.GRAY));
    }

    /** Shortens {@code text} to fit {@code width}, ending in an ellipsis. */
    static String elide(Graphics2D g, String text, int width) {
        String value = text == null ? "" : text;
        if (g.getFontMetrics().stringWidth(value) <= width) return value;
        while (value.length() > 1
                && g.getFontMetrics().stringWidth(value + "…") > width) {
            value = value.substring(0, value.length() - 1);
        }
        return value + "…";
    }

    private static Color ui(String key, Color fallback) {
        Color value = UIManager.getColor(key);
        return value == null ? fallback : value;
    }
}
