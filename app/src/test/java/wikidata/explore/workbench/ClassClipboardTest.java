package wikidata.explore.workbench;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import static org.junit.jupiter.api.Assertions.*;

class ClassClipboardTest {

    private static GeneratedProjectModel project(String name) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name(name);
        project.rootClass(new GeneratedClassModel("Root"));
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.addField("birthName", FieldType.STRING, FieldCardinality.SINGLE);
        project.addClass(person);
        return project;
    }

    @Test void nothingCanBePastedBeforeSomethingIsCopied() {
        ClassClipboard clipboard = new ClassClipboard();

        assertTrue(clipboard.isEmpty());
        assertFalse(clipboard.canPasteInto(project("Nobel")));
        assertEquals("Copy a class first.", clipboard.refusalFor(project("Nobel")));
    }

    @Test void aCopiedClassCanBePastedIntoAnotherProject() {
        ClassClipboard clipboard = new ClassClipboard();
        clipboard.copy(project("Oscars"), "Person");

        assertFalse(clipboard.isEmpty());
        assertEquals("Person", clipboard.className());
        assertEquals("Oscars", clipboard.sourceName());
        assertTrue(clipboard.canPasteInto(project("Nobel")));
    }

    /**
     * Pasting a class back where it came from is pasting it onto itself. Wanting a second
     * one is answered by renaming the first, not by the paste inventing a name.
     */
    @Test void aClassCannotBePastedWhereItWasCopiedFrom() {
        ClassClipboard clipboard = new ClassClipboard();
        clipboard.copy(project("Oscars"), "Person");

        assertFalse(clipboard.canPasteInto(project("Oscars")));
        assertTrue(clipboard.refusalFor(project("Oscars")).contains("rename"),
                clipboard.refusalFor(project("Oscars")));
    }

    /** Identity is the folder key, so punctuation and case cannot dodge the refusal. */
    @Test void theSameProjectIsRecognisedThroughItsName() {
        ClassClipboard clipboard = new ClassClipboard();
        clipboard.copy(project("U.S. Presidents"), "Person");

        assertFalse(clipboard.canPasteInto(project("us presidents")));
    }

    /**
     * The clipboard holds a snapshot. Editing the project afterwards must not change what
     * a pending paste will produce — following later edits is what an import does, and
     * this is the other one.
     */
    @Test void whatWasCopiedIsNotChangedByLaterEditsToItsSource() {
        GeneratedProjectModel source = project("Oscars");
        ClassClipboard clipboard = new ClassClipboard();
        clipboard.copy(source, "Person");

        source.findClass("Person").addField(
                "addedAfterCopying", FieldType.STRING, FieldCardinality.SINGLE);

        assertEquals(1, clipboard.snapshot().findClass("Person").fields().size(),
                "the copy is of the class as it was when copied");
    }

    @Test void copyingAClassThatIsNotThereChangesNothing() {
        ClassClipboard clipboard = new ClassClipboard();
        clipboard.copy(project("Oscars"), "Nonexistent");
        assertTrue(clipboard.isEmpty());

        clipboard.copy(project("Oscars"), "Person");
        clipboard.copy(project("Oscars"), "");

        assertEquals("Person", clipboard.className(),
                "a refused copy leaves what was already copied alone");
    }
}
