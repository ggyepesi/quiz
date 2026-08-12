package wikidata.explore.workbench;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One place for rendering a Wikidata id (QID or PID) as a clickable link, so
 * every panel — result tables, the class panel's type/relation/subtype fields,
 * the Explore member lists — links consistently to the entity's Wikidata page.
 * Opens via {@link objectview.utils.BrowserLauncher}.
 */
public final class WikidataLinks {

    private static final Color LINK = new Color(0, 80, 200);

    private WikidataLinks() {
    }

    /** The value→URL mapping for card rendering: a bare Wikidata id becomes a link,
     *  anything else is left alone. One supplier so every view links identically
     *  instead of each remembering to. */
    public static java.util.function.Function<Object, String> valueLinker() {
        return value -> value instanceof CharSequence text && isId(text.toString().trim())
                ? url(text.toString().trim()) : null;
    }

    public static boolean isId(String s) {
        return s != null && wikidata.WikidataIds.isId(s.trim());
    }

    public static String url(String id) {
        if (id == null) {
            return null;
        }
        id = id.trim();
        if (wikidata.WikidataIds.isPid(id)) {
            return "https://www.wikidata.org/wiki/Property:" + id;
        }
        if (wikidata.WikidataIds.isQid(id)) {
            return "https://www.wikidata.org/wiki/" + id;
        }
        return null;
    }

    public static void open(String id) {
        String u = url(id);
        if (u != null) {
            objectview.utils.BrowserLauncher.open(u);
        }
    }

    /** Make a {@link JLabel} a link to the id supplied at click time (a PID/QID
     *  that may change as the field is edited). No-op click when there's no id. */
    public static void linkify(JLabel label, Supplier<String> idSupplier) {
        label.setForeground(LINK);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                open(idSupplier.get());
            }
        });
    }

    /** Render a table column of ids as clickable links (renderer + click + hand
     *  cursor). {@code idColumn} is the MODEL column index. */
    public static void installOnColumn(JTable table, int idColumn) {
        if (idColumn < 0 || idColumn >= table.getColumnModel().getColumnCount()) {
            return;
        }
        table.getColumnModel().getColumn(idColumn).setCellRenderer(new LinkRenderer());
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                int vr = table.rowAtPoint(e.getPoint());
                int vc = table.columnAtPoint(e.getPoint());
                if (vr < 0 || table.convertColumnIndexToModel(vc) != idColumn) {
                    return;
                }
                Object v = table.getValueAt(vr, vc);
                open(v == null ? null : v.toString());
            }
        });
        table.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int vc = table.columnAtPoint(e.getPoint());
                boolean on = vc >= 0
                        && table.convertColumnIndexToModel(vc) == idColumn;
                table.setCursor(Cursor.getPredefinedCursor(
                        on ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });
    }

    // --- Inline linking: build a run of text where ids and labelled entities are
    //     clickable, then wrap it in one widget. A label always travels WITH its
    //     id (we never have a label whose qid/pid we don't already hold), so we
    //     link the (id, label) PAIR directly — no fuzzy phrase matching. ---

    private static final Pattern ID = Pattern.compile("\\b[PQ]\\d+\\b");

    /**
     * A labelled Wikidata entity kept together with its id — the unit of linking.
     * {@code id} is a QID or PID; {@code label} is the human text shown.
     */
    public record Linked(String id, String label) {
        public String html() {
            return linkHtml(id, label);
        }
    }

    public static Linked of(String id, String label) {
        return new Linked(id, label);
    }

    /**
     * HTML fragment for free {@code text}, linking any bare QID/PID token it
     * contains (an id is self-identifying). Plain runs are HTML-escaped. Not
     * wrapped in {@code <html>} so fragments compose; feed to {@link #pane}.
     */
    public static String html(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        Matcher m = ID.matcher(text);
        int cursor = 0;
        while (m.find()) {
            out.append(escape(text.substring(cursor, m.start())));
            out.append(linkHtml(m.group(), m.group()));
            cursor = m.end();
        }
        out.append(escape(text.substring(cursor)));
        return out.toString();
    }

    /**
     * Anchor showing {@code label}, linking to {@code id}'s Wikidata page — the
     * "keep them together" primitive. Falls back to the escaped label when the id
     * isn't a linkable QID/PID (so it's always safe to call).
     */
    public static String linkHtml(String id, String label) {
        String shown = escape(label == null || label.isBlank()
                ? (id == null ? "" : id) : label);
        String u = url(id);
        return u == null ? shown : "<a href=\"" + u + "\">" + shown + "</a>";
    }

    /**
     * A read-only, label-styled widget rendering an HTML fragment (from
     * {@link #html} / {@link #linkHtml} / {@link Linked#html}) with its links
     * clickable — the "just wrap it" entry point. Newlines become breaks.
     */
    public static JEditorPane pane(String htmlFragment) {
        JEditorPane pane = new JEditorPane();
        pane.setContentType("text/html");
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setBorder(null);
        Font f = UIManager.getFont("Label.font");
        if (f != null) {
            pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            pane.setFont(f);
        }
        pane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED
                    && e.getURL() != null) {
                objectview.utils.BrowserLauncher.open(e.getURL().toString());
            }
        });
        setHtml(pane, htmlFragment);
        return pane;
    }

    /** Replace a {@link #pane}'s content with a new HTML fragment. */
    public static void setHtml(JEditorPane pane, String htmlFragment) {
        pane.setText("<html><body>"
                + (htmlFragment == null ? "" : htmlFragment).replace("\n", "<br>")
                + "</body></html>");
        pane.setCaretPosition(0);
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default  -> b.append(c);
            }
        }
        return b.toString();
    }

    private static final class LinkRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object value, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t, value, sel, foc, row, col);
            String text = value == null ? "" : value.toString();
            if (!sel && isId(text)) {
                setForeground(LINK);
                setText("<html><u>" + text + "</u></html>");
            } else {
                setText(text);
            }
            return this;
        }
    }
}
