package process.swing;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Cursor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A slow action must visibly become work, leave the EDT free to paint that state,
 * and restore its trigger on every terminal path. */
class SwingActionRunnerTest {

    @Test
    void aRunningActionNamesItsWorkAndCannotBeStartedAgain() throws Exception {
        JButton button = new JButton("Create quiz");
        JPanel owner = new JPanel();
        Cursor readyCursor = owner.getCursor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean workRanOnEdt = new AtomicBoolean(true);

        SwingUtilities.invokeAndWait(() -> SwingActionRunner.run(
                button, "Preparing quiz…", owner,
                () -> {
                    workRanOnEdt.set(SwingUtilities.isEventDispatchThread());
                    started.countDown();
                    release.await();
                    return "ready";
                },
                result -> completed.countDown(),
                failure -> completed.countDown()));

        assertTrue(started.await(2, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("Preparing quiz…", button.getText());
            assertFalse(button.isEnabled());
            assertEquals(Cursor.WAIT_CURSOR, owner.getCursor().getType());
        });
        assertFalse(workRanOnEdt.get(), "expensive work must not block Swing painting");

        release.countDown();
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("Create quiz", button.getText());
            assertTrue(button.isEnabled());
            assertSame(readyCursor, owner.getCursor());
        });
    }

    @Test
    void aFailedActionAlsoRestoresItsTrigger() throws Exception {
        JButton button = new JButton("Create quiz");
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<Throwable> reported = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> SwingActionRunner.run(
                button, "Preparing quiz…", null,
                () -> { throw new IllegalStateException("broken"); },
                result -> { },
                failure -> {
                    reported.set(failure);
                    failed.countDown();
                }));

        assertTrue(failed.await(2, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("Create quiz", button.getText());
            assertTrue(button.isEnabled());
        });
        assertEquals("broken", reported.get().getMessage());
    }
}
