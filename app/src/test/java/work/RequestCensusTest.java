package work;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run's result names what became of every failed request (#397). Ownership and
 * recovery are facts in the log tree, never guesses from equal request text.
 */
class RequestCensusTest {

    private static final String STARS = "SELECT ?value WHERE { ?value wdt:P59 wd:Q10476 }";
    private static final String UNIT = "SELECT ?sym WHERE { ?value p:P1215 ?u }";

    @Test void recoveredAndAbandonedRequestsAreCountedAndNamedByTheirWork() {
        LogNode run = new LogNode(LogKind.WORKFLOW, "Generate Constellations");
        WorkflowRecorder recorder = new WorkflowRecorder(run);
        recorder.start();
        LogNode stars = recorder.beginQuery("Load edge stars", "SPARQL", "", Map.of());
        LogNode auriga = recorder.beginSubquery(stars, "stars <- Q10476", "SPARQL", STARS);
        attempt(recorder, auriga, "[WIKIDATA] SPARQL 14 GET", STARS, LogStatus.FAILED);
        attempt(recorder, auriga, "[WIKIDATA] SPARQL 15 GET", STARS, LogStatus.OK);
        recorder.completeSubquery(auriga, "6 (retry)", LogStatus.OK);
        LogNode unit = recorder.beginSubquery(stars, "Unit of Star.apparentMagnitude",
                "SPARQL", UNIT);
        attempt(recorder, unit, "[WIKIDATA] SPARQL 2 GET", UNIT, LogStatus.FAILED);
        attempt(recorder, unit, "[WIKIDATA] SPARQL 3 GET", UNIT, LogStatus.FAILED);
        recorder.completeSubquery(unit, "unit not checked", LogStatus.FAILED);

        RequestCensus census = RequestCensus.of(run);

        assertEquals("3 request(s) failed: 1 recovered by retry or split (stars <- Q10476); "
                + "2 gave up (Unit of Star.apparentMagnitude)", census.describe());

        recorder.complete(stars, LogStatus.OK, "507 child objects", null);
        recorder.finish(QueryStatus.OK, "1224 objects", null);
        assertEquals("1224 objects\nREQUESTS\t" + census.describe(), run.summary(),
                "the run's own result says it");
    }

    @Test void aRunWhoseRequestsAllSucceededSaysNothingMore() {
        LogNode run = new LogNode(LogKind.WORKFLOW, "Generate");
        WorkflowRecorder recorder = new WorkflowRecorder(run);
        recorder.start();
        LogNode step = recorder.beginQuery("Load", "SPARQL", "", Map.of());
        attempt(recorder, step, "[WIKIDATA] SPARQL 1 GET", STARS, LogStatus.OK);
        recorder.complete(step, LogStatus.OK, "done", null);
        recorder.finish(QueryStatus.OK, "92 objects", null);

        assertNull(RequestCensus.of(run).describe());
        assertEquals("92 objects", run.summary());
    }

    @Test void equalRequestTextInUnrelatedWorkDoesNotImplyRecovery() {
        LogNode run = new LogNode(LogKind.WORKFLOW, "Generate");
        WorkflowRecorder recorder = new WorkflowRecorder(run);
        recorder.start();
        LogNode failedWork = recorder.beginSubquery(
                run, "First star load", "SPARQL", STARS);
        attempt(recorder, failedWork, "[WIKIDATA] SPARQL 1 GET", STARS,
                LogStatus.FAILED);
        recorder.completeSubquery(failedWork, "gave up", LogStatus.FAILED);
        LogNode successfulWork = recorder.beginSubquery(
                run, "Unrelated star load", "SPARQL", STARS);
        attempt(recorder, successfulWork, "[WIKIDATA] SPARQL 2 GET", STARS,
                LogStatus.OK);
        recorder.completeSubquery(successfulWork, "done", LogStatus.OK);

        assertEquals("1 request(s) failed: 0 recovered by retry or split; 1 gave up "
                + "(First star load)", RequestCensus.of(run).describe());
    }

    @Test void aSplitIsRecoveryEvenThoughItsReplacementRequestsDiffer() {
        LogNode run = new LogNode(LogKind.WORKFLOW, "Explore");
        WorkflowRecorder recorder = new WorkflowRecorder(run);
        recorder.start();
        LogNode original = recorder.beginSubquery(
                run, "wbgetentities 50 entities", "API", "ids=Q1|Q2");
        attempt(recorder, original, "[WIKIDATA] API 7", "ids=Q1%7CQ2",
                LogStatus.FAILED);
        recorder.completeSubquery(original, "Adapted into 2 smaller batches",
                LogStatus.ADAPTED);
        LogNode replacement = recorder.beginSubquery(
                run, "wbgetentities 25 entities", "API", "ids=Q1");
        attempt(recorder, replacement, "[WIKIDATA] API 8", "ids=Q1", LogStatus.OK);
        recorder.completeSubquery(replacement, "done", LogStatus.OK);

        String census = RequestCensus.of(run).describe();
        assertEquals("1 request(s) failed: 1 recovered by retry or split "
                + "(wbgetentities 50 entities)", census);
    }

    @Test void anAttemptWithNoLogicalOwnerIsNamedByItselfAndNotInventedAsRecovered() {
        LogNode run = new LogNode(LogKind.WORKFLOW, "Explore");
        WorkflowRecorder recorder = new WorkflowRecorder(run);
        recorder.start();
        attempt(recorder, run, "[WIKIDATA] SPARQL 7 POST", STARS, LogStatus.FAILED);

        String census = RequestCensus.of(run).describe();
        assertTrue(census.endsWith("1 gave up ([WIKIDATA] SPARQL 7 POST)"), census);
    }

    @Test void anAbandonedRequestIsClosedAndReportedBeforeTheWorkflowFinishes() {
        LogNode run = new LogNode(LogKind.WORKFLOW, "Generate");
        WorkflowRecorder recorder = new WorkflowRecorder(run);
        recorder.start();
        LogNode work = recorder.beginQuery("Load stars", "SPARQL", STARS, Map.of());
        recorder.beginRequest(work, "[WIKIDATA] SPARQL 1 GET", "SPARQL", STARS);

        recorder.finish(QueryStatus.OK, "92 objects", null);

        LogNode request = work.steps().iterator().next();
        assertEquals(LogStatus.FAILED, request.status());
        assertEquals(LogStatus.PARTIAL, work.status());
        assertTrue(run.summary().contains("1 gave up (Load stars)"), run.summary());
    }

    private static void attempt(WorkflowRecorder recorder, LogNode parent, String title,
                                String request, LogStatus status) {
        LogNode node = recorder.beginRequest(parent, title, "SPARQL", request);
        recorder.completeSubquery(node, status == LogStatus.OK ? "OK" : "FAILED", status);
    }
}
