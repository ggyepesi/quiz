package process.swing;

import process.CancellationToken;

import javax.swing.SwingUtilities;
import java.awt.Window;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Bridges Process-owned synchronous input to a MODELESS Swing dialog. Only the process
 * worker waits; the EDT remains available for logs, validation, navigation and cancellation.
 */
public final class SwingProcessInput {
    private SwingProcessInput() {}

    @FunctionalInterface
    public interface DialogOpener<T> {
        Window open(Consumer<T> completed);
    }

    public static <T> T await(
            CancellationToken cancellation, DialogOpener<T> opener) throws Exception {
        CompletableFuture<T> answer = new CompletableFuture<>();
        AtomicReference<Window> dialog = new AtomicReference<>();
        Runnable open = () -> dialog.set(opener.open(answer::complete));
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Process input must wait off the Swing EDT");
        }
        SwingUtilities.invokeAndWait(open);
        try {
            while (true) {
                cancellation.throwIfCancelled();
                try {
                    return answer.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Poll cancellation while the modeless dialog remains open.
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof Exception ex) throw ex;
                    if (cause instanceof Error fatal) throw fatal;
                    throw new IllegalStateException(cause);
                }
            }
        } finally {
            if (!answer.isDone()) {
                Window window = dialog.get();
                if (window != null) {
                    SwingUtilities.invokeLater(window::dispose);
                }
            }
        }
    }
}
