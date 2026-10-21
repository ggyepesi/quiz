package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import javax.swing.JComponent;
import javax.swing.JTextField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A single configured id is shown once as a labelled link and changed explicitly. */
class WikidataIdLinesTest {

    @Test void aConfiguredIdIsNotAlsoPaintedAsTheBackingTextField() {
        JTextField field = new JTextField("Q5");
        JComponent wrapped = WikidataIdLines.qid(field);
        WikidataSingleIdEditor editor = (WikidataSingleIdEditor) wrapped;

        assertEquals("Q5", editor.valueText().shownText());
        assertFalse(descendant(editor, field));
    }

    @Test void changeAndRemoveWriteTheBackingConfiguration() {
        JTextField field = new JTextField("Q5");
        WikidataSingleIdEditor editor =
                (WikidataSingleIdEditor) WikidataIdLines.qid(field);

        editor.changeButton().doClick();
        editor.draftField().setText("q42");
        editor.setButton().doClick();
        assertEquals("Q42", field.getText());

        editor.removeButton().doClick();
        assertEquals("", field.getText());
    }

    @Test void aFieldTheEditorHidesTakesItsRendererWithIt() {
        JTextField field = new JTextField("Q5");
        JComponent wrapped = WikidataIdLines.qid(field);
        field.setVisible(false);
        dispatch(field, java.awt.event.ComponentEvent.COMPONENT_HIDDEN);
        assertFalse(wrapped.isVisible());
        field.setVisible(true);
        dispatch(field, java.awt.event.ComponentEvent.COMPONENT_SHOWN);
        assertTrue(wrapped.isVisible());
    }

    @Test void aLockedEditorKeepsTheConfiguredLinkButNotItsChangeActions() {
        JTextField field = new JTextField("Q5");
        WikidataSingleIdEditor wrapped =
                (WikidataSingleIdEditor) WikidataIdLines.qid(field);
        EditableComponents.setEditable(wrapped, false);
        assertEquals("Q5", wrapped.valueText().shownText());
        assertFalse(wrapped.changeButton().isEnabled());
        assertFalse(wrapped.removeButton().isEnabled());
    }

    private static boolean descendant(java.awt.Container root, java.awt.Component sought) {
        for (java.awt.Component child : root.getComponents()) {
            if (child == sought) return true;
            if (child instanceof java.awt.Container nested) {
                if (descendant(nested, sought)) return true;
            }
        }
        return false;
    }

    private static void dispatch(java.awt.Component component, int id) {
        java.awt.event.ComponentEvent event = new java.awt.event.ComponentEvent(component, id);
        for (java.awt.event.ComponentListener listener : component.getComponentListeners()) {
            if (id == java.awt.event.ComponentEvent.COMPONENT_HIDDEN) {
                listener.componentHidden(event);
            } else {
                listener.componentShown(event);
            }
        }
    }
}
