package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.query.core.Datasource;
import wikidata.explore.query.core.WikidataAccess;
import work.QueryContext;
import work.LogKind;
import work.LogNode;
import work.LogStatus;
import work.WorkflowRecorder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepGenerationLogStatusTest {

    @Test void aGroupWithAFailedChildCannotCloseAsOk() throws Exception {
        LogNode root = new LogNode(LogKind.WORKFLOW, "Generate domain");
        WorkflowRecorder recorder = new WorkflowRecorder(root);
        recorder.start();
        QueryContext context = new QueryContext().withRecorder(recorder);

        context.step("Generate", "Domain", null, Map.of(), step -> {
            GenerationLog log = StepGenerationLog.of(context, step);
            try (GenerationLog.Group group = log.group("Load Nominee.type (P31)")) {
                group.subquery("batch 1", "https://example/1", "ok");
                group.subqueryFailed("batch 2", "https://example/2",
                        "FATAL: Connection reset | URL: https://example/2");
            }
            return null;
        });

        LogNode query = root.steps().iterator().next();
        LogNode group = query.steps().iterator().next();
        assertEquals(LogStatus.PARTIAL, group.status());
        assertTrue(group.summary().contains("1 failed"), group.summary());
        assertTrue(group.summary().contains("Connection reset"), group.summary());
        assertTrue(!group.summary().contains("https://example/2"), group.summary());
    }

    @Test void aNormallyReturningQueryCanExplicitlyFinishPartial() throws Exception {
        LogNode root = new LogNode(LogKind.WORKFLOW, "Generate domain");
        WorkflowRecorder recorder = new WorkflowRecorder(root);
        recorder.start();
        QueryContext context = new QueryContext().withRecorder(recorder);

        context.step("Generate", "Domain", null, Map.of(), step -> {
            step.partial("26058 objects; Nominee.type (P31) unresolved");
            return null;
        });

        LogNode query = root.steps().iterator().next();
        assertEquals(LogStatus.PARTIAL, query.status());
        assertTrue(query.summary().contains("Nominee.type"), query.summary());
    }

    @Test void endpointAttemptsStayUnderTheLogicalWorkThatIssuedThem() throws Exception {
        LogNode root = new LogNode(LogKind.WORKFLOW, "Generate domain");
        WorkflowRecorder recorder = new WorkflowRecorder(root);
        recorder.start();
        QueryContext context = new QueryContext().withRecorder(recorder);

        context.step("Generate", "Domain", null, Map.of(), step -> {
            GenerationLog log = StepGenerationLog.of(context, step);
            GenerationLog.Running work = log.subqueryStarted(
                    "Unit of Star.apparentMagnitude", "SELECT ?sym WHERE { }");
            java.util.function.Consumer<String> transport =
                    WikidataAccess.structuredRequestLog(Datasource.WIKIDATA, log);
            java.util.function.Consumer<String> captured =
                    ((wikidata.WikidataSparqlClient.CapturableRequestLog) transport)
                            .capture();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                captured.accept("[SPARQL 1] START GET\nSELECT ?sym WHERE { }");
                captured.accept("[SPARQL 1] ERROR timeout");
            }).join();
            work.failed("unit not checked");
            return null;
        });

        LogNode query = root.steps().iterator().next();
        LogNode work = query.steps().iterator().next();
        LogNode request = work.steps().iterator().next();
        assertEquals("Unit of Star.apparentMagnitude", work.title());
        assertEquals(LogKind.REQUEST, request.kind());
        assertEquals(LogStatus.FAILED, request.status());
    }

    /** The batch executor starts a unit on a worker and finishes it on its coordinator.
     *  Finished work must leave the worker's owner stack, or a later request on that
     *  worker is filed under — and recovered by — a batch that has already ended. */
    @Test void workFinishedOnAnotherThreadNoLongerOwnsRequests() throws Exception {
        LogNode root = new LogNode(LogKind.WORKFLOW, "Generate domain");
        WorkflowRecorder recorder = new WorkflowRecorder(root);
        recorder.start();
        QueryContext context = new QueryContext().withRecorder(recorder);

        context.step("Generate", "Domain", null, Map.of(), step -> {
            GenerationLog log = StepGenerationLog.of(context, step);
            java.util.concurrent.ExecutorService worker =
                    java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                GenerationLog.Running batch = worker.submit(() -> log.subqueryStarted(
                        "wbgetentities 50 entities", "ids=Q1|Q2")).get();
                batch.done("50 entities");   // on this thread, as the coordinator does
                worker.submit(() -> log.requestStarted("[WIKIDATA] SPARQL 9 GET",
                        "SELECT ?x WHERE { }").failed("timeout")).get();
            } finally {
                worker.shutdownNow();
            }
            return null;
        });

        LogNode query = root.steps().iterator().next();
        LogNode batch = query.steps().iterator().next();
        assertEquals("wbgetentities 50 entities", batch.title());
        assertTrue(batch.steps().isEmpty(), "the finished batch owns no later request");
        assertEquals(2, query.steps().size(), "the request is the step's own");
    }

    @Test void anAdaptiveBatchKeepsItsTerminalOutcome() throws Exception {
        LogNode root = new LogNode(LogKind.WORKFLOW, "Generate domain");
        WorkflowRecorder recorder = new WorkflowRecorder(root);
        recorder.start();
        QueryContext context = new QueryContext().withRecorder(recorder);

        context.step("Generate", "Domain", null, Map.of(), step -> {
            GenerationLog log = StepGenerationLog.of(context, step);
            log.batchProgress().started("wbgetentities 50 entities", "ids=Q1|Q2")
                    .adapted("Adapted into 2 smaller batches");
            return null;
        });

        LogNode query = root.steps().iterator().next();
        assertEquals(LogStatus.ADAPTED,
                query.steps().iterator().next().status());
    }
}
