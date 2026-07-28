package process;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Hierarchical cancellation: a child can be cancelled alone; a parent cancels all children. */
public final class CancellationToken {
    private final CancellationToken parent;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<CancellationToken> children = new CopyOnWriteArrayList<>();

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
            children.forEach(CancellationToken::cancel);
        }
    }

    public boolean isCancelled() {
        return cancelled.get() || (parent != null && parent.isCancelled());
    }

    public void throwIfCancelled() {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Process cancelled");
        }
    }
}
