package wikidata.explore.workbench;

import dataset.DomainStorage;
import wikidata.explore.model.GeneratedProjectModel;

/**
 * A class copied from one project, waiting to be pasted into another.
 *
 * <p>Holds a snapshot of the project it was copied from, not a live reference to it. A
 * clipboard that followed later edits would paste something the reader never copied, and
 * an import is where following a model's changes is the point — this is the other one.
 */
public final class ClassClipboard {
    private GeneratedProjectModel snapshot;
    private String className = "";
    private String sourceName = "";

    /** Takes what is on the clipboard now, replacing anything already there. */
    public void copy(GeneratedProjectModel project, String classToCopy) {
        if (project == null || classToCopy == null || classToCopy.isBlank()
                || project.findClass(classToCopy) == null) {
            return;
        }
        snapshot = project.copy();
        className = classToCopy;
        sourceName = project.name();
    }

    public boolean isEmpty() {
        return snapshot == null || className.isBlank();
    }

    public GeneratedProjectModel snapshot() { return snapshot; }
    public String className() { return className; }
    public String sourceName() { return sourceName; }

    /**
     * Whether this can be pasted into {@code target}. Not into the project it came from:
     * that would paste a class onto itself, and wanting two of them is answered by
     * renaming the first, not by the paste inventing a name.
     */
    public boolean canPasteInto(GeneratedProjectModel target) {
        if (isEmpty() || target == null) return false;
        return !DomainStorage.key(sourceName).equals(DomainStorage.key(target.name()));
    }

    /** Why a paste is refused, for a reader who can see the button is disabled. */
    public String refusalFor(GeneratedProjectModel target) {
        if (isEmpty()) return "Copy a class first.";
        if (!canPasteInto(target)) {
            return className + " was copied from " + sourceName + ", which is where you "
                    + "are.\n\nA class cannot be pasted onto itself. To have a second "
                    + "one, rename this class first, then paste.";
        }
        return "";
    }
}
