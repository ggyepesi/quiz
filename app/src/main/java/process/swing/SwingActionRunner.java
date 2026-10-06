package process.swing;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.Component;
import java.awt.Cursor;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Runs one potentially slow Swing action without leaving its trigger looking usable.
 * The action is disabled and names the work before the EDT is released; completion,
 * failure and restoration all run on the EDT.
 */
public final class SwingActionRunner {
    private SwingActionRunner() {}

    public static <T> void run(
            AbstractButton trigger,
            String runningText,
            Component cursorOwner,
            Callable<T> work,
            Consumer<T> completed,
            Consumer<Throwable> failed) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Swing actions must start on the EDT");
        }
        if (trigger == null || work == null || !trigger.isEnabled()) return;

        String readyText = trigger.getText();
        Cursor readyCursor = cursorOwner == null ? null : cursorOwner.getCursor();
        trigger.setText(runningText);
        trigger.setEnabled(false);
        if (cursorOwner != null) {
            cursorOwner.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        }

        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override protected void done() {
                try {
                    T result = get();
                    if (completed != null) completed.accept(result);
                } catch (Throwable error) {
                    Throwable cause = error instanceof ExecutionException
                            && error.getCause() != null ? error.getCause() : error;
                    if (failed != null) failed.accept(cause);
                } finally {
                    trigger.setText(readyText);
                    trigger.setEnabled(true);
                    if (cursorOwner != null) cursorOwner.setCursor(readyCursor);
                }
            }
        }.execute();
    }
}
