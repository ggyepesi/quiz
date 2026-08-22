package work;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Hierarchical cancellation: a child can be cancelled alone; a parent cancels all children. */
public final class CancellationToken {
    private final CancellationToken parent;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<CancellationToken> children = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public CancellationToken() {
        this(null);
    }

    private CancellationToken(CancellationToken parent) {
        this.parent = parent;
    }

    public CancellationToken child() {
        CancellationToken child = new CancellationToken(this);
        children.add(child);
        if (isCancelled()) {
            child.cancel();
        }
        return child;
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            List<Runnable> toNotify;
            synchronized (listeners) {
                toNotify = List.copyOf(listeners);
                listeners.clear();
            }
            toNotify.forEach(CancellationToken::runCancellationListener);
            children.forEach(CancellationToken::cancel);
        }
    }

    /**
     * Registers active-I/O cleanup (for example SPARQL future cancellation). The returned
     * registration should be closed when the run ends. Registration is race-safe: an action
     * added after cancellation has started is invoked immediately.
     */
    public Registration onCancel(Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        synchronized (listeners) {
            if (!isCancelled()) {
                listeners.add(action);
                return () -> listeners.remove(action);
            }
        }
        // Run outside the monitor: transport cancellation may invoke arbitrary callbacks.
        runCancellationListener(action);
        return () -> { };
    }

    public boolean isCancelled() {
        return cancelled.get() || (parent != null && parent.isCancelled());
    }

    public void throwIfCancelled() {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Process cancelled");
        }
    }

    private static void runCancellationListener(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException ignored) {
            // Cancellation is best-effort cleanup; one transport must not prevent the
            // remaining transports and child processes from being cancelled.
        }
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override void close();
    }
}
