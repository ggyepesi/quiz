package wikidata.explore.query.swing;

import work.Query;
import work.QueryContext;
import work.QueryResultSink;
import work.LogListener;
import wikidata.explore.query.workflow.QueryWorkflow;

import javax.swing.*;

public class SwingQueryRunner {

    private final QueryContext context;
    private final LogListener logListener;

    private final java.util.List<AbstractButton> runButtons =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private final java.util.List<AbstractButton> cancelButtons =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.List<java.util.function.Consumer<Boolean>> runningListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private SwingWorker<?, ?> currentWorker;
    private Runnable cancelAction = () -> {};

    public SwingQueryRunner(
            QueryContext context,
            LogListener logListener) {
        this.context = context;
        this.logListener = logListener;
    }

    public void cancelAction(Runnable cancelAction) {
        this.cancelAction =
                cancelAction == null ? () -> {} : cancelAction;
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

    public void onRunningChanged(java.util.function.Consumer<Boolean> listener) {
        if (listener != null) runningListeners.add(listener);
    }

    public <R> void wireButton(
            AbstractButton button,
            QueryResultSink<R> sink,
            java.util.function.Supplier<Query<R>> querySupplier,
            java.util.function.Consumer<Exception> onError) {

        registerRunButton(button);

        QueryWorkflow<R> workflow =
                new QueryWorkflow<>(
                        context,
                        sink,
                        logListener);

        button.addActionListener(e -> {
            if (isRunning()) {
                return;
            }

            Query<R> query;

            try {
                query = querySupplier.get();
                if (query == null) {
                    return;
                }
            } catch (Exception ex) {
                if (onError != null) {
                    onError.accept(ex);
                }
                return;
            }

            run(workflow, query, onError);
        });
    }

    public <R> void run(
            QueryWorkflow<R> workflow,
            Query<R> query,
            java.util.function.Consumer<Exception> onError) {

        if (isRunning()) {
            return;
        }

        setRunning(true);

        currentWorker =
                new SwingWorker<Void, Void>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        workflow.run(query);
                        return null;
                    }

                    @Override
                    protected void done() {
                        try {
                            get();
                        } catch (java.util.concurrent.CancellationException ignored) {
                        } catch (java.util.concurrent.ExecutionException ex) {
                            Throwable cause =
                                    ex.getCause() == null ? ex : ex.getCause();

                            if (!QueryWorkflow.isCancellation(cause)
                                    && onError != null) {
                                onError.accept(cause instanceof Exception e
                                                       ? e
                                                       : ex);
                            }
                        } catch (Exception ex) {
                            if (onError != null) {
                                onError.accept(ex);
                            }
                        } finally {
                            currentWorker = null;
                            setRunning(false);
                        }
                    }
                };

        currentWorker.execute();
    }

    /** Run a query with this runner's shared context and log listener. */
    public <R> void run(
            Query<R> query,
            QueryResultSink<R> sink,
            java.util.function.Consumer<Exception> onError) {
        run(new QueryWorkflow<>(context, sink, logListener), query, onError);
    }

    /**
     * A lookup that does not count as "the runner is busy": it never becomes the current
     * worker, so it neither disables the run buttons nor locks configuration, and a lock
     * held elsewhere does not stop it.
     *
     * <p>That exemption is the whole point — filling a panel with what Wikidata says
     * about one QID has to stay available while a long operation runs — and it is only
     * sound for a READ. A quiet query that changed the model or the pool would let a
     * user edit configuration underneath a running generation, which is exactly what the
     * lock exists to prevent.
     */
    public <R> void runQuiet(
            Query<R> query,
            QueryResultSink<R> sink,
            java.util.function.Consumer<Exception> onError) {

        QueryWorkflow<R> workflow =
                new QueryWorkflow<>(context, sink, logListener);

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                workflow.run(query);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (java.util.concurrent.CancellationException ignored) {
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause =
                            ex.getCause() == null ? ex : ex.getCause();

                    if (!QueryWorkflow.isCancellation(cause)
                            && onError != null) {
                        onError.accept(cause instanceof Exception e
                                               ? e
                                               : ex);
                    }
                } catch (Exception ex) {
                    if (onError != null) {
                        onError.accept(ex);
                    }
                }
            }
        }.execute();
    }

    public void cancel() {
        if (currentWorker != null && !currentWorker.isDone()) {
            currentWorker.cancel(true);
        }
        cancelAction.run();
    }

    public boolean isRunning() {
        return currentWorker != null && !currentWorker.isDone();
    }

    public QueryContext context() {
        return context;
    }

    public LogListener logListener() {
        return logListener;
    }

    private void setRunning(boolean running) {
        SwingUtilities.invokeLater(() -> {
            for (AbstractButton b : runButtons) {
                b.setEnabled(!running);
            }

            for (AbstractButton b : cancelButtons) {
                b.setEnabled(running);
            }
            runningListeners.forEach(listener -> listener.accept(running));
        });
    }
}
