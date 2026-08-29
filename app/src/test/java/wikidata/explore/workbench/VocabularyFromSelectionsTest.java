package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.VocabularySelection;
import workbench.WorkbenchSelections;

import javax.swing.JButton;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The multiple-selection side of reusable selections: entities are collected wherever
 * the reader finds them, and named once as a vocabulary. Searching "Nobel Prize" and
 * picking the categories one at a time is the case this exists for.
 */
class VocabularyFromSelectionsTest {

    @Test void entitiesCollectedInExploreBecomeAVocabulary() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        WorkbenchSelections selections = new WorkbenchSelections();
        SelectionViewerPanel panel = new SelectionViewerPanel(project, null, null);
        panel.selections(selections);

        JButton use = button(panel, "Use selected entities");
        assertNotNull(use, "the panel offers the multiple-selection action");
        assertTrue(!use.isEnabled(), "nothing selected yet");

        selections.entity("Q80061", "Nobel Prize in Physiology or Medicine");
        selections.entity("Q38104", "Nobel Prize in Physics");
        selections.entity("Q44585", "Nobel Prize in Chemistry");
        assertTrue(use.isEnabled(), "three selected");

        named(panel, "vocabularyFromSelectionsName").setText("Prize");
        use.doClick();

        VocabularySelection created = project.selections().stream()
                .filter(s -> "Prize".equals(s.name()))
                .filter(VocabularySelection.class::isInstance)
                .map(VocabularySelection.class::cast)
                .findFirst().orElseThrow();
        assertEquals(3, created.valueQids().size(), "every selected entity is a value");
        assertTrue(created.valueQids().contains("Q38104"));

        selections.clearEntity();
        selections.entity("Q7191", "Nobel Prize");
        named(panel, "vocabularyFromSelectionsName").setText("Prize");
        use.doClick();

        assertEquals(1, project.selections().stream()
                .filter(s -> "Prize".equals(s.name())).count(),
                "correcting a named vocabulary replaces it rather than shadowing it");
        assertEquals(java.util.List.of("Q7191"),
                ((VocabularySelection) project.findSelection("Prize")).valueQids());
    }

    private static JButton button(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton candidate
                    && text.equals(candidate.getText())) return candidate;
            if (component instanceof Container child) {
                JButton found = button(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Two rows carry a name box of the same shape, so this one is found by name. */
    private static JTextField named(Container root, String name) {
        JTextField found = search(root, name);
        if (found == null) throw new AssertionError("no text field named " + name);
        return found;
    }

    // The search returns null so a branch without the field does not end the search;
    // only the caller decides that missing is a failure.
    private static JTextField search(Container root, String name) {
        for (Component component : root.getComponents()) {
            if (component instanceof JTextField candidate
                    && name.equals(candidate.getName())) return candidate;
            if (component instanceof Container child) {
                JTextField found = search(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
