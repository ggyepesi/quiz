package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A refused batch records AS refused.
 *
 * <p>Every caller passed {@code log::subquery} as the batch sink, which files an entry
 * with OK status. A failed batch therefore appeared as an OK node whose summary merely
 * began with "FAILED" — so a run in which 54 label batches were refused rendered
 * entirely green, and the failures were only found by grepping the text.
 *
 * <p>A log that shows a failure as a success is worse than one that shows nothing: it
 * answers the question "did anything go wrong" with a confident no.
 */
class BatchSinkStatusTest {

    private record Entry(String kind, String title, String detail) {}

    /** A log that records which channel each entry arrived on. */
    private static GenerationLog recording(List<Entry> entries) {
        return new GenerationLog() {
            @Override public void message(String text) {
                entries.add(new Entry("message", text, ""));
            }
            @Override public void subquery(String title, String request, String summary) {
                entries.add(new Entry("subquery", title, summary));
            }
            @Override public void subqueryFailed(String title, String request, String error) {
                entries.add(new Entry("subqueryFailed", title, error));
            }
        };
    }

    @Test void aFailedBatchGoesToTheFailureChannelNotTheOkOne() {
        List<Entry> entries = new ArrayList<>();
        WikidataApiClient.BatchLog sink = recording(entries).batchSink();

        sink.logged("wbgetentities 1/2", "url", "50/50 entities (700 ms)");
        sink.failed("wbgetentities 2/2", "url", "FAILED: HTTP 429");

        assertEquals("subquery", entries.get(0).kind());
        assertEquals("subqueryFailed", entries.get(1).kind(),
                     "a refused batch must not be filed as an OK entry");
    }

    /** The default keeps a sink that does not distinguish the two from losing the
     *  entry altogether — degrade to the old rendering, never to silence. */
    @Test void aSinkThatDoesNotDistinguishStillReceivesTheEntry() {
        List<String> seen = new ArrayList<>();
        WikidataApiClient.BatchLog plain = (title, request, summary) -> seen.add(title);

        plain.failed("wbgetentities 1/1", "url", "FAILED: HTTP 429");

        assertEquals(List.of("wbgetentities 1/1"), seen);
    }

    @Test void aBatchIsOpenedBeforeItCompletes() {
        List<String> events = new ArrayList<>();
        GenerationLog log = new GenerationLog() {
            @Override public void message(String text) { }
            @Override public void subquery(String title, String request, String summary) { }
            @Override public Running subqueryStarted(String title, String request) {
                events.add("started:" + title);
                return new Running() {
                    @Override public void done(String summary) { events.add("done:" + summary); }
                    @Override public void failed(String error) { events.add("failed:" + error); }
                };
            }
        };

        WikidataApiClient.BatchLog.Running running =
                log.batchSink().started("wbgetentities 50 entities", "url");
        assertEquals(List.of("started:wbgetentities 50 entities"), events);

        running.detail("Full retry after split");
        running.done("ok (1200 ms)");
        assertEquals(2, events.size());
        assertTrue(events.get(1).contains("Full retry after split"));
        assertTrue(events.get(1).contains("ok (1200 ms)"));
    }

    @Test void failureDetailsPrecedeTheTerminalOutcome() {
        assertEquals("Attempt 1/2 failed\nUnavailable retry budget exhausted",
                WikidataApiClient.BatchLog.withDetails(
                        List.of("Attempt 1/2 failed"),
                        "Unavailable retry budget exhausted"));
    }
}
