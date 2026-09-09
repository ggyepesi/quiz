package wikidata.explore.query.swing;

import org.junit.jupiter.api.Test;
import work.LogKind;
import work.LogNode;

import javax.swing.SwingUtilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowLogWindowLiveUpdateTest {

    @Test void aGenerationBurstQueuesOneUiDeliveryInsteadOfOnePerLogMutation()
            throws Exception {
        WorkflowLogWindow window = new WorkflowLogWindow();
        LogNode workflow = new LogNode(LogKind.WORKFLOW, "Generate Positions");
        CountDownLatch edtOccupied = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtOccupied.countDown();
            try {
                releaseEdt.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(edtOccupied.await(3, TimeUnit.SECONDS));

        Thread generation = new Thread(() -> {
            window.logChanged(workflow, true, false);
            for (int i = 0; i < 10_000; i++) {
                window.logChanged(workflow, false, false);
            }
        }, "generation");
        generation.start();
        generation.join(TimeUnit.SECONDS.toMillis(10));
        assertTrue(!generation.isAlive());

        assertEquals(1, window.pendingRootCount(),
                "the latest state of one workflow is one pending UI update");
        assertEquals(0, window.deliveryPassCount(),
                "the bounded refresh interval leaves the event thread free for clicks");

        releaseEdt.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (window.deliveryPassCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(1, window.workflowCount());
        assertTrue(window.deliveryPassCount() <= 2,
                "10,001 mutations should be rendered as a bounded batch, not 10,001 cards");
    }
}
