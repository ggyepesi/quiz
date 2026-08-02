package quiz.ui;

import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    /** A dialog result can be submitted by a button or by closing the window, but its
     * consumer must run exactly once. Centralizing that lifecycle keeps all review
     * dialogs consistent. */
    public static <T> Consumer<T> completion(
            JDialog dialog, Consumer<T> onDone) {
        Consumer<T> handler = onDone == null ? ignored -> { } : onDone;
        AtomicBoolean completed = new AtomicBoolean();
        return result -> {
            if (completed.compareAndSet(false, true)) {
                handler.accept(result);
                dialog.dispose();
            }
        };
    }

    /** Complete a result dialog with {@code closedResult} when its window is closed. */
    public static <T> void completeOnClose(
            JDialog dialog, Consumer<T> completion, Supplier<T> closedResult) {
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                completion.accept(closedResult.get());
            }
        });
    }
}
