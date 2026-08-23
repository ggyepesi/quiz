package work;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A log node is a {@code Viewable}, so the query-log window renders it through the same
 * card machinery as domain data — and its {@code messages} are declared as a LIST
 * precisely so a long body renders as a collapsible collection.
 *
 * <p>Which meant the worker thread was appending to a plain {@code ArrayList} while the
 * event dispatch thread iterated it. A run that logs 324 dropped self-nominations into
 * one node is exactly the moment someone opens the log to watch, and the iterator is
 * fail-fast: {@code ConcurrentModificationException} on AWT-EventQueue-0, killing that
 * event mid-render. Its sibling {@code steps} had been made copy-on-write for this
 * reason; {@code messages} was missed.
 */
class LogMessagesAreRenderSafeTest {

    @Test void appendingWhileTheListIsBeingIteratedDoesNotBreakTheReader() {
        // Deterministic form of the crash: an iterator is live and a message arrives.
        // A fail-fast list throws on the very next step; this one must not.
        LogNode node = new LogNode(LogKind.QUERY, "Reify P1411");
        node.appendMessage("first\nsecond");

        Iterator<String> reading = node.messages().iterator();
        assertEquals("first", reading.next());

        node.appendMessage("DROPPED self-nomination Q105883400$whale");

        assertEquals("second", reading.next(),
                "the render pass in flight must finish against what it started with");
        assertTrue(!reading.hasNext(),
                "and must not see the line that arrived after it began");
    }

    @Test void aLongRunOfMessagesArrivingDuringRenderingLosesNothing() throws Exception {
        LogNode node = new LogNode(LogKind.QUERY, "Reify P1411");
        int lines = 2000;
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            started.countDown();
            for (int i = 0; i < lines; i++) {
                node.appendMessage("DROPPED self-nomination " + i);
            }
        }, "generation");

        Thread reader = new Thread(() -> {
            try {
                started.await();
                // What Card/ValueRenderer do: walk the collection to decide how to
                // render it, over and over as the panel repaints.
                for (int pass = 0; pass < 400; pass++) {
                    List<String> seen = new ArrayList<>();
                    for (String line : node.messages()) {
                        seen.add(line);
                    }
                }
            } catch (Throwable failure) {
                readerFailure.set(failure);
            }
        }, "render");

        reader.start();
        writer.start();
        writer.join(TimeUnit.SECONDS.toMillis(20));
        reader.join(TimeUnit.SECONDS.toMillis(20));

        assertNull(readerFailure.get(),
                "rendering must survive a run that is still logging: "
                        + readerFailure.get());
        assertEquals(lines, node.messages().size(),
                "and every line the run emitted is still there — two threads lazily "
                        + "creating the list could previously lose one thread's lines");
    }

    @Test void aNodeWithNoMessagesRendersAsAbsentNotAsAnEmptyRow() {
        // Holding the list eagerly is only free because Card skips an empty collection
        // exactly as it skips a null one. If that ever stops being true, every log node
        // grows a "messages (0)" row.
        LogNode node = new LogNode(LogKind.QUERY, "Reify P1411");

        assertTrue(node.messages().isEmpty());
        assertTrue(!LogText.toText(node).contains("|"),
                "an empty message list contributes no message block to the text log "
                        + "either: " + LogText.toText(node));
    }

    @Test void blankTextIsNotAMessage() {
        LogNode node = new LogNode(LogKind.QUERY, "Reify P1411");

        node.appendMessage(null);
        node.appendMessage("   ");

        assertTrue(node.messages().isEmpty());
    }
}
