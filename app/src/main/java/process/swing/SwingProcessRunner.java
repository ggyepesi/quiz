package process.swing;

import work.CancellationToken;
import process.Process;
import process.ProcessInputHandler;
import process.ProcessOutcome;
import process.ProcessRunner;
import work.QueryContext;
import work.LogListener;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Swing host for Process; cancellation belongs to the running process, not its UI button. */
public final class SwingProcessRunner {
    /**
     * Extends the disabled lifetime of this runner's actions beyond request execution.
     * A result-review window is still part of the action: closing this handle says that
     * the reader has finished with that result and the action may be offered again.
     */
    public interface RunButtonHold extends AutoCloseable {
        @Override void close();
    }

    private final QueryContext queries;
    private final LogListener logs;
    private final ProcessInputHandler inputs;
    private final List<AbstractButton> runButtons = new CopyOnWriteArrayList<>();
    private final List<AbstractButton> cancelButtons = new CopyOnWriteArrayList<>();
    private final List<Consumer<Boolean>> runningListeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger runButtonHolds = new AtomicInteger();
    private volatile SwingWorker<?, ?> worker;
    private volatile Thread workerThread;
    private volatile CancellationToken cancellation;

    public SwingProcessRunner(
            QueryContext queries, LogListener logs, ProcessInputHandler inputs) {
        this.queries = queries;
        this.logs = logs;
        this.inputs = inputs;
    }

    public void registerRunButton(AbstractButton button) {
        if (button != null && !runButtons.contains(button)) {
            runButtons.add(button);
            button.setEnabled(runButtonsEnabled());
        }
    }

    /**
     * Keep every action registered with this runner disabled after its process has
     * completed. Holds compose: the actions become available only after the process is
     * idle and every hold has been closed.
     */
    public RunButtonHold holdRunButtons() {
        runButtonHolds.incrementAndGet();
        refreshButtons();
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            int remaining = runButtonHolds.decrementAndGet();
            if (remaining < 0) {
                runButtonHolds.set(0);
                throw new IllegalStateException("Run-button hold released more than once");
            }
            refreshButtons();
        };
    }

    public void registerCancelButton(AbstractButton button) {
        if (button != null && !cancelButtons.contains(button)) {
            cancelButtons.add(button);
            button.setEnabled(isRunning());
            SwingCancellationConfirmation.wire(
                    button, button, this::isRunning, this::cancel);
        }
    }

    public void onRunningChanged(Consumer<Boolean> listener) {
        if (listener != null) runningListeners.add(listener);
    }

    public <R> void run(
            Process<R> process,
            Consumer<ProcessOutcome<R>> completed,
            Consumer<Throwable> unexpectedError) {
        if (isRunning()) return;
        cancellation = new CancellationToken();
        setRunning(true);
        ProcessRunner runner = new ProcessRunner(queries, logs, inputs);
        AtomicReference<ProcessOutcome<R>> terminal = new AtomicReference<>();
        worker = new SwingWorker<ProcessOutcome<R>, Void>() {
            @Override protected ProcessOutcome<R> doInBackground() {
                workerThread = Thread.currentThread();
                try {
                    ProcessOutcome<R> outcome = runner.run(process, cancellation);
                    terminal.set(outcome);
                    return outcome;
                } finally {
                    workerThread = null;
                }
            }

            @Override protected void done() {
                try {
                    ProcessOutcome<R> outcome = get();
                    if (completed != null) completed.accept(outcome);
                } catch (CancellationException cancelled) {
                    // SwingWorker#get discards a return value after cancel(true). The
                    // Process outcome still contains any completed provider results.
                    if (terminal.get() != null && completed != null) {
                        completed.accept(terminal.get());
                    }
                } catch (Throwable error) {
                    if (unexpectedError != null) unexpectedError.accept(error);
                } finally {
                    worker = null;
                    cancellation = null;
                    setRunning(false);
                }
            }
        };
        worker.execute();
    }

    public void cancel() {
        CancellationToken token = cancellation;
        if (token != null) token.cancel();
        // Interrupt the work without marking SwingWorker itself cancelled. A cancelled
        // SwingWorker discards doInBackground's return value, including accepted partial
        // results; the Process must be allowed to return its explicit CANCELLED outcome.
        Thread active = workerThread;
        if (active != null) active.interrupt();
    }

    public boolean isRunning() {
        SwingWorker<?, ?> active = worker;
        return active != null && !active.isDone();
    }

    private void setRunning(boolean running) {
        onEdt(() -> {
            runButtons.forEach(button -> button.setEnabled(
                    !running && runButtonHolds.get() == 0));
            cancelButtons.forEach(button -> button.setEnabled(running));
            runningListeners.forEach(listener -> listener.accept(running));
        });
    }

    private boolean runButtonsEnabled() {
        return !isRunning() && runButtonHolds.get() == 0;
    }

    private void refreshButtons() {
        onEdt(() -> runButtons.forEach(
                button -> button.setEnabled(runButtonsEnabled())));
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
    }
}
