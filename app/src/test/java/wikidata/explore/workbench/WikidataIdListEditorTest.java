package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.ui.WikidataLabels;

import javax.swing.JLabel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A configured id list has one labelled-link representation and explicit edits. */
class WikidataIdListEditorTest {

    @Test void configuredQidsAreRowsAndNotASecondPaintedTextField() {
        JTextField storage = new JTextField("Q5 Q42");
        WikidataIdListEditor editor = new WikidataIdListEditor(
                storage, WikidataIdListEditor.Kind.QID);

        assertEquals(java.util.List.of("Q5", "Q42"), editor.shownIds());
        assertFalse(descendant(editor, storage),
                "the backing text is the model, not a second visible representation");
    }

    @Test void addAndRemoveWriteTheOneBackingConfiguration() {
        JTextField storage = new JTextField("Q5");
        WikidataIdListEditor editor = new WikidataIdListEditor(
                storage, WikidataIdListEditor.Kind.QID);

        editor.draftField().setText("Q42, q2");
        editor.addButton().doClick();
        assertEquals("Q5 Q42 Q2", storage.getText());
        assertEquals(java.util.List.of("Q5", "Q42", "Q2"), editor.shownIds());

        editor.idList().setSelectedValue("Q42", true);
        editor.removeButton().doClick();
        assertEquals("Q5 Q2", storage.getText());
    }

    @Test void theDeclaredIdentifierKindRejectsTheOtherKind() {
        JTextField storage = new JTextField();
        WikidataIdListEditor qids = new WikidataIdListEditor(
                storage, WikidataIdListEditor.Kind.QID);

        qids.draftField().setText("P31");
        assertFalse(qids.addButton().isEnabled());
        qids.addButton().doClick();
        assertEquals("", storage.getText());
    }

    @Test void programmaticModelChangesRefreshTheRows() {
        JTextField storage = new JTextField("Q5");
        WikidataIdListEditor editor = new WikidataIdListEditor(
                storage, WikidataIdListEditor.Kind.QID);

        storage.setText("Q2 Q3");
        assertEquals(java.util.List.of("Q2", "Q3"), editor.shownIds());
    }

    @Test void qidsAndPidsUseTheSameLabelledRenderer() {
        WikidataLabels.seed(Map.of("Q5", "human", "P31", "instance of"));
        WikidataIdListEditor qids = new WikidataIdListEditor(
                new JTextField("Q5"), WikidataIdListEditor.Kind.QID);
        WikidataIdListEditor pids = new WikidataIdListEditor(
                new JTextField("P31"), WikidataIdListEditor.Kind.PID);

        assertTrue(rendered(qids).contains("human (Q5)"), rendered(qids));
        assertTrue(rendered(pids).contains("instance of (P31)"), rendered(pids));
    }

    private static String rendered(WikidataIdListEditor editor) {
        Component rendered = editor.idList().getCellRenderer()
                .getListCellRendererComponent(editor.idList(),
                        editor.idList().getModel().getElementAt(0), 0, false, false);
        return ((JLabel) rendered).getText();
    }

    private static boolean descendant(Container root, Component sought) {
        for (Component child : root.getComponents()) {
            if (child == sought) return true;
            if (child instanceof Container nested && descendant(nested, sought)) return true;
        }
        return false;
    }
}
