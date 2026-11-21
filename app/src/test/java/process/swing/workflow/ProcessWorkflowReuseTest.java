package process.swing.workflow;

import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import java.awt.Component;
import java.awt.Window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A still-open result belongs to the action and subject that produced it and is reused
 * even after its runner has stopped. Disposing that window is what permits a fresh
 * lifecycle.
 *
 * <p>Reuse once keyed on the action id alone. Every graph shares the id
 * {@code discover-graph}, so with graph A's results open, running graph B only re-focused
 * A's window and B never ran. A Plan page was reused the same way, showing a plan for
 * configuration that had since changed.</p>
 */
class ProcessWorkflowReuseTest {

    private static final Window OWNER = null;

    private static SwingProcessWorkflow.Registration at(
            String id, String subject, ProcessWorkflowState.Stage stage) {
        return new SwingProcessWorkflow.Registration(id, subject, OWNER, () -> stage);
    }

    @Test void everyWorkflowUsesAMinimizableFrame() throws NoSuchMethodException {
        assertEquals(JFrame.class, SwingProcessWorkflow.class.getMethod("start",
                Component.class, process.swing.SwingProcessRunner.class,
                ProcessWorkflowAction.class).getReturnType());
    }

    @Test void anOpenWindowForTheSameActionAndSubjectIsReused() {
        assertTrue(SwingProcessWorkflow.sameWorkflow("discover-graph", "decl:a",
                at("discover-graph", "decl:a", ProcessWorkflowState.Stage.RESULTS),
                true, OWNER));
    }

    @Test void anotherGraphsOpenResultDoesNotBlockRunningThisGraph() {
        assertFalse(SwingProcessWorkflow.sameWorkflow("discover-graph", "decl:b",
                at("discover-graph", "decl:a", ProcessWorkflowState.Stage.RESULTS),
                true, OWNER));
    }

    @Test void aDisposedOrDifferentWorkflowDoesNotBlockANewRun() {
        assertFalse(SwingProcessWorkflow.sameWorkflow("generate-domain", "",
                at("generate-domain", "", ProcessWorkflowState.Stage.RESULTS),
                false, OWNER));
        assertFalse(SwingProcessWorkflow.sameWorkflow("generate-domain", "",
                at("build-project", "", ProcessWorkflowState.Stage.RESULTS),
                true, OWNER));
    }

    @Test void aWorkflowBelongsOnlyToTheWindowThatStartedIt() {
        assertTrue(SwingProcessWorkflow.sameOwner(null, null));
        assertFalse(SwingProcessWorkflow.sameOwner(null, new Object()));
    }

    @Test void onlyAWindowWithWorkInItIsKeptAPlanPageIsReplaced() {
        assertFalse(SwingProcessWorkflow.holdsWork(
                at("generate-domain", "", ProcessWorkflowState.Stage.PLAN)));
        assertTrue(SwingProcessWorkflow.holdsWork(
                at("generate-domain", "", ProcessWorkflowState.Stage.RUNNING)));
        assertTrue(SwingProcessWorkflow.holdsWork(
                at("generate-domain", "", ProcessWorkflowState.Stage.RESULTS)));
    }
}
