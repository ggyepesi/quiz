package quiz.ui;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import javax.swing.JComponent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
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

    /** One point-of-action confirmation for every Save/Load workflow. The producer owns
     * the exact description; this shared UI owns the verb, Cancel behavior and owner. */
    public static boolean confirmPersistence(
            Component parent, String verb, String description) {
        String action = verb == null || verb.isBlank() ? "Continue" : verb;
        int answer = JOptionPane.showOptionDialog(owner(parent), wrapped(description), action,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, new Object[]{action, "Cancel"}, "Cancel");
        return answer == 0;
    }

    /** Consistent readable body for dialogs containing explanations or exact file paths. */
    public static JComponent wrapped(String text) {
        JTextArea area = new JTextArea(text == null ? "" : text);
        area.setEditable(false);
        area.setOpaque(false);
        area.setFocusable(true); // paths remain selectable/copyable
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setColumns(64);
        area.setBorder(null);
        int visualLines = 0;
        for (String line : area.getText().split("\\R", -1)) {
            visualLines += Math.max(1, (line.length() + 63) / 64);
        }
        area.setRows(Math.min(18, Math.max(2, visualLines)));
        return area;
    }

    /** The best owner for a new dialog, including a visible modeless child of the
     * requested window. A modal prompt owned by the main frame while such a child
     * remains above it is an invisible input blocker on macOS. */
    public static Window owner(Component parent) {
        Window requested = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        return owner(requested, active, Window::isVisible);
    }

    static Window owner(Window requested, Window active, Predicate<Window> visible) {
        return chooseOwner(requested, active, Dialogs::root,
                window -> Arrays.asList(window.getOwnedWindows()), visible);
    }

    static <T> T chooseOwner(T requested, T active,
                             Function<T, T> root,
                             Function<T, List<T>> children,
                             Predicate<T> visible) {
        if (requested == null) return active;
        T starting = active != null && root.apply(active) == root.apply(requested)
                ? active : requested;
        return frontmostVisibleDescendant(starting, children, visible);
    }

    private static <T> T frontmostVisibleDescendant(
            T owner, Function<T, List<T>> children, Predicate<T> visible) {
        List<T> owned = children.apply(owner);
        // Later-created siblings are the best available z-order approximation when
        // clicking the main frame has just replaced the previously active child.
        for (int i = owned.size() - 1; i >= 0; i--) {
            T child = owned.get(i);
            if (visible.test(child)) {
                return frontmostVisibleDescendant(child, children, visible);
            }
        }
        return owner;
    }

    private static Window root(Window window) {
        Window root = window;
        while (root != null && root.getOwner() != null) root = root.getOwner();
        return root;
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
