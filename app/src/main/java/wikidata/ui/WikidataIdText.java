package wikidata.ui;

import javax.swing.JEditorPane;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Read-only text in which every Wikidata id is a link reading {@code label (Q5)} (#404):
 * the one component for an id a configuration editor shows. Labels come from
 * {@link WikidataLabels}; an id whose label is not yet known links as the bare id and
 * is relabelled in place when its label arrives.
 *
 * <p>Two uses: {@link #setPlainText} for a sentence that mentions ids (a status line),
 * and {@link #following} for the line beside an editable id field, which shows exactly
 * the ids typed there.
 */
public final class WikidataIdText extends JEditorPane {

    /** The most links a field's line shows; the rest are counted, not listed. */
    static final int SHOWN = 10;

    private final Runnable relabel = this::render;
    private String text = "";
    private boolean idsOnly;

    public WikidataIdText() {
        setContentType("text/html");
        setEditable(false);
        setOpaque(false);
        setBorder(null);
        Font font = UIManager.getFont("Label.font");
        if (font != null) {
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            setFont(font);
        }
        addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED
                    && event.getURL() != null) {
                objectview.utils.BrowserLauncher.open(event.getURL().toString());
            }
        });
        render();
    }

    /** The line beside {@code field}: a link for every id typed in it, at once. Only
     *  the label lookup waits until typing settles, so a half-typed id is not fetched. */
    public static WikidataIdText following(JTextComponent field) {
        WikidataIdText line = new WikidataIdText();
        line.idsOnly = true;
        line.setPlainText(field.getText());
        Timer settle = new Timer(400, event -> WikidataLabels.request(ids(line.text)));
        settle.setRepeats(false);
        DocumentListener follow = new DocumentListener() {
            private void changed() {
                line.text = field.getText();
                line.render();
                settle.restart();
            }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        };
        field.getDocument().addDocumentListener(follow);
        field.addPropertyChangeListener("document", event -> {
            if (event.getOldValue() instanceof javax.swing.text.Document old) {
                old.removeDocumentListener(follow);
            }
            field.getDocument().addDocumentListener(follow);
            line.setPlainText(field.getText());
        });
        return line;
    }

    /** Show {@code value}, linking every id in it. */
    public void setPlainText(String value) {
        text = value == null ? "" : value;
        WikidataLabels.request(ids(text));
        render();
    }

    /** Whether this is the line beside an editable field rather than a sentence. */
    public boolean followsField() {
        return idsOnly;
    }

    public String plainText() {
        return text;
    }

    /** What a reader sees, without markup: {@code human (Q5)}. */
    public String shownText() {
        return idsOnly ? String.join(" · ", shownIds()) : WikidataLinks.labelledPlain(text);
    }

    @Override public void setForeground(Color color) {
        super.setForeground(color);
        if (text != null) render();
    }

    @Override public void addNotify() {
        super.addNotify();
        WikidataLabels.onChange(relabel);
        render();
    }

    @Override public void removeNotify() {
        WikidataLabels.removeOnChange(relabel);
        super.removeNotify();
    }

    private List<String> shownIds() {
        List<String> shown = new ArrayList<>();
        for (String id : ids(text)) {
            if (shown.size() == SHOWN) break;
            String label = WikidataLabels.label(id);
            shown.add(label == null ? id : label + " (" + id + ")");
        }
        return shown;
    }

    private void render() {
        String body;
        if (idsOnly) {
            List<String> ids = new ArrayList<>(ids(text));
            List<String> links = new ArrayList<>();
            for (String id : ids.subList(0, Math.min(SHOWN, ids.size()))) {
                links.add(WikidataLinks.labelledLink(id));
            }
            body = String.join(" · ", links)
                    + (ids.size() > SHOWN ? " · +" + (ids.size() - SHOWN) + " more" : "");
        } else {
            body = WikidataLinks.labelledHtml(text);
        }
        Color color = getForeground();
        String style = color == null ? "" : String.format(
                " style=\"color:#%02x%02x%02x\"", color.getRed(), color.getGreen(),
                color.getBlue());
        super.setText("<html><body" + style + ">" + body.replace("\n", "<br>")
                + "</body></html>");
        setCaretPosition(0);
    }

    static Set<String> ids(String value) {
        Set<String> ids = new LinkedHashSet<>();
        if (value == null) return ids;
        Matcher matcher = WikidataLinks.ID.matcher(value);
        while (matcher.find()) ids.add(matcher.group());
        return ids;
    }
}
