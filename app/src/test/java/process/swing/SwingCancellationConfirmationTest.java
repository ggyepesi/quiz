package process.swing;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A stray Cancel click cannot stop expensive work without a second deliberate choice. */
class SwingCancellationConfirmationTest {
    @Test void theSafeChoiceIsTheDialogDefault() {
        assertEquals("Cancel running process", SwingCancellationConfirmation.TITLE);
        assertEquals("Cancel the running process?\n"
                        + "It will stop as soon as the current operation can be interrupted.",
                SwingCancellationConfirmation.MESSAGE);
        assertEquals(List.of("Keep running", "Cancel process"),
                List.of(SwingCancellationConfirmation.CHOICES));
        assertEquals(0, SwingCancellationConfirmation.KEEP_RUNNING_CHOICE);
        assertEquals(1, SwingCancellationConfirmation.CANCEL_CHOICE);
    }

    @Test void decliningCancellationKeepsTheProcessRunning() {
        JButton button = new JButton("Cancel process");
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger cancellations = new AtomicInteger();
        SwingCancellationConfirmation.wire(button, running::get,
                () -> false, cancellations::incrementAndGet);

        button.doClick();

        assertEquals(0, cancellations.get());
        assertTrue(running.get());
    }

    @Test void confirmingCancellationStopsTheActiveProcess() {
        JButton button = new JButton("Cancel process");
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger cancellations = new AtomicInteger();
        SwingCancellationConfirmation.wire(button, running::get, () -> true, () -> {
            cancellations.incrementAndGet();
            running.set(false);
        });

        button.doClick();

        assertEquals(1, cancellations.get());
        assertFalse(running.get());
    }

    @Test void anIdleRunnerDoesNotAskOrCancel() {
        JButton button = new JButton("Cancel process");
        AtomicInteger confirmations = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        SwingCancellationConfirmation.wire(button, () -> false, () -> {
            confirmations.incrementAndGet();
            return true;
        }, cancellations::incrementAndGet);

        button.doClick();

        assertEquals(0, confirmations.get());
        assertEquals(0, cancellations.get());
    }
}
