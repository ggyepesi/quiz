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
            if (!running.getAsBoolean()) return;
            if (confirmation.getAsBoolean() && running.getAsBoolean()) cancel.run();
        });
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
