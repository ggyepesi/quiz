package wikidata.explore.query.swing;

import objectview.render.RenderContext;
import org.junit.jupiter.api.Test;
import work.LogKind;
import work.LogNode;
import work.LogStatus;
import work.QueryStatus;
import work.WorkflowRecorder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Follow running step" (#398): the path to every running step is open while it runs,
 * and only what following opened is folded again — a step the reader opened stays open.
 */
class RunningStepFollowerTest {

    @Test void theRunningPathIsOpenWhileItRunsAndFoldsWhenItFinishes() {
        LogNode run = new LogNode(LogKind.WORKFLOW, "Generate Constellations");
        WorkflowRecorder recorder = new WorkflowRecorder(run);
        recorder.start();
        LogNode stars = recorder.beginQuery("Load edge stars", "SPARQL", "", Map.of());
        LogNode auriga = recorder.beginSubquery(stars, "stars <- Q10476", "SPARQL", "SELECT 1");
        RenderContext context = new RenderContext();
        RunningStepFollower follower = new RunningStepFollower();

        assertTrue(follower.follow(run, context));
        assertTrue(context.isCardExpanded(run), "the run's card is open");
        assertTrue(context.isCollectionExpanded(run.steps(), false));
        assertTrue(context.isExpanded(stars, false), "the running step is open");
        assertTrue(context.isCollectionExpanded(stars.steps(), false));
        assertTrue(context.isExpanded(auriga, false), "and so is its running sub-query");
        assertFalse(follower.follow(run, context), "nothing moved, so nothing to rebuild");

        recorder.completeSubquery(auriga, "6", LogStatus.OK);
        assertTrue(follower.follow(run, context));
        assertFalse(context.isExpanded(auriga, false), "a finished sub-query folds again");
        assertTrue(context.isExpanded(stars, false), "its still-running parent stays open");

        recorder.complete(stars, LogStatus.OK, "507 child objects", null);
        recorder.finish(QueryStatus.OK, "1224 objects", null);
        assertTrue(follower.follow(run, context));
        assertFalse(context.isCardExpanded(run), "a finished run folds back to its title");
        assertFalse(context.isExpanded(stars, false));
    }

    @Test void foldingBackRestoresTheViewsOwnSettingInsteadOfClosingIt() {
        LogNode run = new LogNode(LogKind.WORKFLOW, "Generate");
        WorkflowRecorder recorder = new WorkflowRecorder(run);
        recorder.start();
        LogNode step = recorder.beginQuery("Load", "SPARQL", "", Map.of());
        RenderContext context = new RenderContext();
        WorkflowLogWindow.applyLogDisclosure(context);
        RunningStepFollower follower = new RunningStepFollower();

        follower.follow(run, context);
        recorder.complete(step, LogStatus.OK, "done", null);
        recorder.finish(QueryStatus.OK, "done", null);
        follower.follow(run, context);

        assertTrue(context.isCollectionExpanded(run.steps(), true),
                "no choice is left behind: the run's steps take the window's setting again");
    }

    @Test void whatTheReaderOpenedIsNeverFoldedByFollowing() {
        LogNode run = new LogNode(LogKind.WORKFLOW, "Generate");
        WorkflowRecorder recorder = new WorkflowRecorder(run);
        recorder.start();
        LogNode step = recorder.beginQuery("Load", "SPARQL", "", Map.of());
        RenderContext context = new RenderContext();
        context.setExpanded(step, true);
        RunningStepFollower follower = new RunningStepFollower();

        follower.follow(run, context);
        follower.stop(context);

        assertTrue(context.isExpanded(step, false), "the reader opened it; it stays open");
        assertFalse(context.isCardExpanded(run), "switching off folds what following opened");
    }
}
