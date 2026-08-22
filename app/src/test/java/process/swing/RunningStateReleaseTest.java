package process.swing;

import org.junit.jupiter.api.Test;
import work.CancellationToken;
import process.Process;
import process.ProcessContext;
import process.ProcessInputHandler;
import process.ProcessOutcome;
import process.ProcessPlan;
import process.ProcessStatus;
import work.LogListener;
import work.LogNode;

import javax.swing.SwingUtilities;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lock that never releases is worse than no lock: the configuration stays frozen and
 * only a restart frees it. The workbench locks on this signal, so what has to be true is
 * that the FINAL value is always false — after a process that fails as loudly as it
 * likes, not only after one that succeeds.
 */
class RunningStateReleaseTest {

    private static final class Recorder implements LogListener {
        @Override public void logChanged(LogNode root, boolean added) { }
    }

    @Test void aFailedProcessStillReportsThatItStopped() throws Exception {
        SwingProcessRunner runner = new SwingProcessRunner(
                null, new Recorder(), ProcessInputHandler.unsupported());
        List<Boolean> states = new CopyOnWriteArrayList<>();
        runner.onRunningChanged(states::add);
        AtomicBoolean finished = new AtomicBoolean();

        runner.run(new Process<String>() {
            @Override public ProcessPlan plan() {
                return new ProcessPlan("boom", "throws", Map.of());
            }
            @Override public ProcessOutcome<String> execute(ProcessContext context) {
                throw new IllegalStateException("the run fails");
            }
        }, outcome -> finished.set(true), error -> finished.set(true));

        awaitRelease(runner, states);

        assertFalse(runner.isRunning(), "the runner is idle after a failure");
        assertTrue(states.contains(Boolean.TRUE), "the lock was taken");
        assertEquals(Boolean.FALSE, states.get(states.size() - 1),
                "and released — a failure is an ending like any other");
    }

    @Test void aCancelledProcessStillReportsThatItStopped() throws Exception {
        SwingProcessRunner runner = new SwingProcessRunner(
                null, new Recorder(), ProcessInputHandler.unsupported());
        List<Boolean> states = new CopyOnWriteArrayList<>();
        runner.onRunningChanged(states::add);

        runner.run(new Process<String>() {
            @Override public ProcessPlan plan() {
                return new ProcessPlan("slow", "waits to be cancelled", Map.of());
            }
            @Override public ProcessOutcome<String> execute(ProcessContext context) {
                CancellationToken token = context.cancellation();
                while (!token.isCancelled()) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return new ProcessOutcome<>(
                        ProcessStatus.CANCELLED, null, null, "cancelled");
            }
        }, outcome -> { }, error -> { });

        while (!runner.isRunning()) Thread.sleep(5);
        runner.cancel();
        awaitRelease(runner, states);

        assertFalse(runner.isRunning());
        assertEquals(Boolean.FALSE, states.get(states.size() - 1),
                "cancelling releases the lock too");
    }

    /** The release is published from the EDT after done() has already run there, so the
     *  only reliable thing to wait for is the signal itself. */
    private static void awaitRelease(
            SwingProcessRunner runner, List<Boolean> states) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && (runner.isRunning() || states.isEmpty()
                        || states.get(states.size() - 1))) {
            Thread.sleep(10);
        }
        SwingUtilities.invokeAndWait(() -> { });
    }
}
