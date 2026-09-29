package wikidata.explore.query.swing;

import org.junit.jupiter.api.Test;
import objectview.render.CardListView;
import objectview.render.RenderContext;
import work.LogKind;
import work.LogNode;
import work.WorkflowRecorder;

import javax.swing.SwingUtilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowLogWindowLiveUpdateTest {

    @Test void searchingADeepRequestExpandsEveryStepThatContainsIt() throws Exception {
        WorkflowRecorder recorder = new WorkflowRecorder(
                new LogNode(LogKind.WORKFLOW, "Generate History"));
        recorder.step("Generate classes", "", "", java.util.Map.of(), outer -> {
            work.LogStep batches = outer.beginGroup("Load referent fields");
            work.LogStep requests = batches.beginGroup("Wikidata API requests");
            LogNode request = requests.beginSubquery(
                    "API 17", "https://www.wikidata.org/w/api.php?ids=Q29971182");
            requests.completeSubquery(request, "OK");
            requests.completeGroup("1 request");
            batches.completeGroup("1 batch");
            return null;
        });
        LogNode root = recorder.root();
        LogNode level1 = root.steps().iterator().next();
        LogNode level2 = level1.steps().iterator().next();
        LogNode level3 = level2.steps().iterator().next();
        LogNode request = level3.steps().iterator().next();

        RenderContext context = new RenderContext();
        context.setCollapsibleCards(true);
        CardListView cards = new CardListView();
        cards.setRenderContext(context);
        cards.addViewable(root);
        SwingUtilities.invokeAndWait(() -> cards.createCardsPanel(1));
        var search = WorkflowLogWindow.queryLogSearch(cards, context);

        assertFalse(context.isExpanded(level1));
        assertFalse(context.isExpanded(level2));
        assertFalse(context.isExpanded(level3));
        SwingUtilities.invokeAndWait(() -> search.runCoordinatedSearch("Q29971182"));

        assertTrue(context.isExpanded(level1), "steps must open");
        assertTrue(context.isExpanded(level2), "steps.steps must open");
        assertTrue(context.isExpanded(level3), "steps.steps.steps must open");
    }

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
