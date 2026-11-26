package domain.workbench.execution.ui;

import org.junit.jupiter.api.Test;
import work.Query;
import work.QueryContext;

import javax.swing.SwingUtilities;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwingQueryRunnerCompletionTest {
    @Test void aCancelledQueryCannotDeliverALateResult() throws Exception {
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var readMayFinish = new Semaphore(0);
        var result = new AtomicReference<String>();
        var failure = new AtomicReference<Exception>();
        var cancellationOnEventThread = new AtomicBoolean();
        var runner = new SwingQueryRunner(new QueryContext(), (root, added, terminal) -> {
            if (terminal) returned.countDown();
        });
        Query<String> read = new Query<>() {
            @Override public String purpose() { return "Read snapshot"; }
            @Override public String skeleton() { return "Read a snapshot that cannot be interrupted"; }
            @Override public Map<String, String> parameters() { return Map.of(); }
            @Override public String execute(QueryContext context) {
                started.countDown();
                readMayFinish.acquireUninterruptibly();
                return "late result";
            }
            @Override public int rowCount(String result) { return 1; }
        };
        SwingUtilities.invokeAndWait(() -> runner.runOnCompletion(read, result::set, failure::set, () -> {
            cancellationOnEventThread.set(SwingUtilities.isEventDispatchThread());
            cancelled.countDown();
        }));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(runner::cancel);
        } finally {
            readMayFinish.release();
        }
        assertTrue(returned.await(5, TimeUnit.SECONDS));
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        assertTrue(cancellationOnEventThread.get());
        SwingUtilities.invokeAndWait(() -> assertFalse(runner.isRunning()));
        assertNull(result.get());
        assertNull(failure.get());
    }
}
