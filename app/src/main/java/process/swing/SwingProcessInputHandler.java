package process.swing;

import work.CancellationToken;
import process.ProcessInputHandler;
import process.ProcessInputRequest;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;

/** Default typed Swing input bridge; richer request types can be handled by a decorator. */
public final class SwingProcessInputHandler implements ProcessInputHandler {
    private final Component owner;

    public SwingProcessInputHandler(Component owner) {
        this.owner = owner;
    }

    @Override public <T> T request(
            ProcessInputRequest<T> request, CancellationToken cancellation)
            throws InvocationTargetException, InterruptedException {
        cancellation.throwIfCancelled();
        AtomicReference<T> answer = new AtomicReference<>();
        Runnable dialog = () -> {
            if (request.responseType() == Boolean.class) {
                int selected = JOptionPane.showConfirmDialog(
                        owner, request.prompt(), request.title(),
                        JOptionPane.OK_CANCEL_OPTION);
                answer.set(request.responseType().cast(selected == JOptionPane.OK_OPTION));
            } else if (request.responseType() == String.class) {
                answer.set(request.responseType().cast(JOptionPane.showInputDialog(
                        owner, request.prompt(), request.title(),
                        JOptionPane.QUESTION_MESSAGE)));
            } else {
                throw new IllegalArgumentException(
                        "Unsupported Swing process input: " + request.responseType());
            }
        };
        if (SwingUtilities.isEventDispatchThread()) dialog.run();
        else SwingUtilities.invokeAndWait(dialog);
        cancellation.throwIfCancelled();
        return answer.get();
    }
}
