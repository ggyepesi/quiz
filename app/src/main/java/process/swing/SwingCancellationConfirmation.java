package process.swing;

import javax.swing.AbstractButton;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.util.function.BooleanSupplier;

/** One guarded user gesture for stopping any running Swing process or query. */
public final class SwingCancellationConfirmation {
    static final String TITLE = "Cancel running process";
    static final String MESSAGE = "Cancel the running process?\n"
            + "It will stop as soon as the current operation can be interrupted.";
    static final Object[] CHOICES = {"Keep running", "Cancel process"};
    static final int KEEP_RUNNING_CHOICE = 0;
    static final int CANCEL_CHOICE = 1;

    private SwingCancellationConfirmation() { }

    public static void wire(
            AbstractButton button,
            Component owner,
            BooleanSupplier running,
            Runnable cancel) {
        wire(button, running, () -> confirm(owner), cancel);
    }

    static void wire(
            AbstractButton button,
            BooleanSupplier running,
            BooleanSupplier confirmation,
            Runnable cancel) {
        if (button == null || running == null || confirmation == null || cancel == null) {
            return;
        }
        button.addActionListener(event -> {
            request(running, confirmation, cancel);
        });
    }

    /**
     * Requests cancellation for a window that is about to close.
     *
     * @return {@code true} when no process is running or cancellation was explicitly
     * confirmed, so the caller may close; {@code false} when the safe "Keep running"
     * choice was made and the observing window must remain open
     */
    public static boolean requestForVisibleRun(Component owner, Runnable cancel) {
        // The workflow calls this only while its visible state is RUNNING. Do not ask
        // SwingWorker whether it has already returned: done() may still be queued on the
        // EDT, leaving the only observer visibly on the Running page in the meantime.
        return requestForVisibleRun(() -> confirm(owner), cancel);
    }

    static boolean requestForVisibleRun(
            BooleanSupplier confirmation, Runnable cancel) {
        return request(() -> true, confirmation, cancel);
    }

    static boolean request(
            BooleanSupplier running,
            BooleanSupplier confirmation,
            Runnable cancel) {
        if (running == null || confirmation == null || cancel == null) return true;
        if (!running.getAsBoolean()) return true;
        if (!confirmation.getAsBoolean()) return false;
        if (running.getAsBoolean()) cancel.run();
        return true;
    }

    private static boolean confirm(Component owner) {
        int choice = JOptionPane.showOptionDialog(owner,
                MESSAGE,
                TITLE,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                CHOICES,
                CHOICES[KEEP_RUNNING_CHOICE]);
        return choice == CANCEL_CHOICE;
    }
}
