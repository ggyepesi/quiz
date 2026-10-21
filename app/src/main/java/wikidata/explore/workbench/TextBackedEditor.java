package wikidata.explore.workbench;

import javax.swing.text.JTextComponent;

/**
 * A control that edits configuration held in one text component it does not paint
 * (#404): the id editors show {@code label (Q5)} rows and write the ids into that text.
 *
 * <p>Code that walks an editor's components — the dirty watcher, the editor lock, the
 * layout dump — asks this one question instead of naming each control: the
 * configuration is {@link #storage()}, and the control's own children (a draft being
 * typed, its buttons) are not configuration.
 */
interface TextBackedEditor {

    /** The text that holds the configuration this control edits. */
    JTextComponent storage();

    /** Lock or unlock the control as a whole, the way {@link EditableComponents} locks
     *  any other configuration surface. */
    void setEditorEditable(boolean editable);
}
