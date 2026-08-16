package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.log.LogKind;
import wikidata.explore.query.log.LogNode;
import wikidata.explore.query.log.LogStatus;
import wikidata.explore.query.log.WorkflowRecorder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepGenerationLogStatusTest {

    @Test void aGroupWithAFailedChildCannotCloseAsOk() throws Exception {
        LogNode root = new LogNode(LogKind.WORKFLOW, "Generate domain");
        WorkflowRecorder recorder = new WorkflowRecorder(root);
        recorder.start();
        QueryContext context = new QueryContext(null, null).withRecorder(recorder);

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
        QueryContext context = new QueryContext(null, null).withRecorder(recorder);

        context.step("Generate", "Domain", null, Map.of(), step -> {
            step.partial("26058 objects; Nominee.type (P31) unresolved");
            return null;
        });

        LogNode query = root.steps().iterator().next();
        assertEquals(LogStatus.PARTIAL, query.status());
        assertTrue(query.summary().contains("Nominee.type"), query.summary());
    }
}
