package wikidata.explore.workbench;

import wikidata.ui.WikidataIdText;

import javax.swing.JComponent;
import javax.swing.text.JTextComponent;

/**
 * The entry points for Wikidata identifiers in configuration (#404): a single id,
 * a list of ids and read-only prose all use the same label cache and link renderer.
 * Labels are presentation and never become configuration.
 */
public final class WikidataIdLines {

    private WikidataIdLines() {
    }

    /** Read-only text whose ids are labelled links — a status line. */
    public static WikidataIdText text() {
        WikidataIdText text = new WikidataIdText();
        EditableComponents.keepReadOnly(text);
        return text;
    }

    /** One configured QID, painted once as its labelled link until Change is pressed. */
    public static JComponent qid(JTextComponent storage) {
        return new WikidataSingleIdEditor(storage, WikidataIdListEditor.Kind.QID);
    }

    /** One configured PID, painted once as its labelled link until Change is pressed. */
    public static JComponent pid(JTextComponent storage) {
        return new WikidataSingleIdEditor(storage, WikidataIdListEditor.Kind.PID);
    }

    /** A configured QID collection, shown once as labelled link rows with Add/Remove. */
    public static JComponent qidList(JTextComponent storage) {
        return new WikidataIdListEditor(storage, WikidataIdListEditor.Kind.QID);
    }

    /** A configured PID collection, shown once as labelled link rows with Add/Remove. */
    public static JComponent pidList(JTextComponent storage) {
        return new WikidataIdListEditor(storage, WikidataIdListEditor.Kind.PID);
    }

}
