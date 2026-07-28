package process.swing;

import process.CancellationToken;
import process.Process;
import process.ProcessInputHandler;
import process.ProcessOutcome;
import process.ProcessRunner;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.log.LogListener;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Swing host for Process; cancellation belongs to the running process, not its UI button. */
public final class SwingProcessRunner {
    private final QueryContext queries;
    private final LogListener logs;
    private final ProcessInputHandler inputs;
    private final List<AbstractButton> runButtons = new CopyOnWriteArrayList<>();
    private final List<AbstractButton> cancelButtons = new CopyOnWriteArrayList<>();
    private volatile SwingWorker<?, ?> worker;
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
            button.setEnabled(!isRunning());
        }
    }

    public void registerCancelButton(AbstractButton button) {
        if (button != null && !cancelButtons.contains(button)) {
            cancelButtons.add(button);
            button.setEnabled(isRunning());
            button.addActionListener(e -> cancel());
        }
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
                ProcessOutcome<R> outcome = runner.run(process, cancellation);
                terminal.set(outcome);
                return outcome;
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
        SwingWorker<?, ?> active = worker;
        if (active != null && !active.isDone()) active.cancel(true);
    }

    public boolean isRunning() {
        SwingWorker<?, ?> active = worker;
        return active != null && !active.isDone();
    }

    private void setRunning(boolean running) {
        SwingUtilities.invokeLater(() -> {
            runButtons.forEach(button -> button.setEnabled(!running));
            cancelButtons.forEach(button -> button.setEnabled(running));
        });
    }
}
