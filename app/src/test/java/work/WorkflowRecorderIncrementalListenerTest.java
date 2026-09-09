package work;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A live log view can update one entry only if the recorder identifies that entry.
 * Reporting every mutation as a change to the root forced the renderer to rebuild
 * every expanded request accumulated by a long generation.
 */
class WorkflowRecorderIncrementalListenerTest {

    @Test void everyNotificationIdentifiesTheNodeThatActuallyChanged() {
        LogNode root = new LogNode(LogKind.WORKFLOW, "Generate Positions");
        WorkflowRecorder recorder = new WorkflowRecorder(root);
        List<Event> events = new ArrayList<>();
        recorder.setListener(new LogListener() {
            @Override public void logChanged(
                    LogNode ignored, boolean added, boolean terminalUpdate) { }

            @Override public void logNodeChanged(
                    LogNode eventRoot, LogNode changed,
                    boolean added, boolean terminalUpdate) {
                events.add(new Event(eventRoot, changed, added, terminalUpdate));
            }
        });

        recorder.added();
        LogNode query = recorder.beginQuery(
                "Load batch", "SPARQL", null, Map.of());
        recorder.append(query, "SELECT * WHERE { ?s ?p ?o }");
        recorder.complete(query, LogStatus.OK, "one row", null);

        assertSame(root, events.get(0).changed());
        assertTrue(events.get(0).added());
        for (Event event : events) assertSame(root, event.root());
        for (Event event : events.subList(1, events.size())) {
            assertSame(query, event.changed(),
                    "query mutations name the query, not the whole workflow");
            assertFalse(event.added(),
                    "only introducing a top-level workflow adds a card");
        }
        assertTrue(events.get(events.size() - 1).terminal());
    }

    private record Event(
            LogNode root, LogNode changed, boolean added, boolean terminal) { }
}
