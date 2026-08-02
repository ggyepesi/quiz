package quiz.ui;

import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Dialog helpers so a blocking (modal) dialog reliably appears on TOP of the window the
 * user is looking at, instead of hiding behind it — a modal dialog that blocks input while
 * buried makes the whole app look frozen.
 */
public final class Dialogs {

    private Dialogs() {}

    /** The best owner for a new dialog: the invoking component's window, else the currently
     *  active window — so the dialog stacks above whatever is actually frontmost (the
     *  component's ancestor may be a modeless tool window sitting behind the main frame). */
    public static Window owner(Component parent) {
        Window w = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        return w != null ? w
                : KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
    }

    /** Raise {@code dialog} to the front and focus it whenever it opens — the fix for a
     *  dialog that would otherwise hide behind another window. Returns the dialog for
     *  chaining, e.g. {@code Dialogs.raiseOnOpen(dialog).setVisible(true)}. */
    public static JDialog raiseOnOpen(JDialog dialog) {
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) {
                dialog.toFront();
                dialog.requestFocus();
            }
        });
        return dialog;
    }
}
