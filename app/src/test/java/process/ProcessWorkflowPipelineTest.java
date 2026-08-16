package process;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessWorkflowPipelineTest {
    private static ProcessWorkflowPipeline pipeline() {
        return new ProcessWorkflowPipeline(List.of(
                new ProcessWorkflowPipeline.Phase("a", "A", "first", List.of("P31")),
                new ProcessWorkflowPipeline.Phase("b", "B", "second", List.of())));
    }

    @Test void startingTheNextPhaseCompletesThePreviousOne() {
        ProcessWorkflowPipeline pipeline = pipeline();
        pipeline.start("a", "10 classes");
        pipeline.start("b", "loading");

        assertEquals(ProcessWorkflowPipeline.Status.COMPLETED,
                pipeline.snapshot().get(0).status());
        assertEquals(ProcessWorkflowPipeline.Status.RUNNING,
                pipeline.snapshot().get(1).status());
    }

    @Test void partialPhaseRemainsVisibleAfterSuccessfulProcessCompletion() {
        ProcessWorkflowPipeline pipeline = pipeline();
        pipeline.start("a", "loading");
        pipeline.partial("a", "Nominee.type P31 — 50 QIDs unavailable");
        pipeline.start("b", "materializing");
        pipeline.complete("b", "26058 instances");
        pipeline.finish(ProcessStatus.PARTIAL, "partial domain");

        assertEquals(ProcessWorkflowPipeline.Status.PARTIAL,
                pipeline.snapshot().get(0).status());
        assertEquals(ProcessWorkflowPipeline.Status.COMPLETED,
                pipeline.snapshot().get(1).status());
    }

    /** Work does not always end inside a phase — a run can fail while saving, after the
     *  last phase completed. The graph must still show where it got to. */
    @Test void aFailureAfterTheLastPhaseIsChargedToThatPhase() throws Exception {
        ProcessWorkflowPipeline pipeline = pipeline();
        pipeline.start("a", "loading");
        pipeline.start("b", "materializing");
        pipeline.complete("b", "26058 instances");
        long tookB = pipeline.snapshot().get(1).elapsedMillis();
        Thread.sleep(20);

        pipeline.finish(ProcessStatus.FAILED, "could not save the snapshot");

        assertEquals(ProcessWorkflowPipeline.Status.COMPLETED,
                pipeline.snapshot().get(0).status(),
                "an earlier phase that finished keeps its own verdict");
        assertEquals(ProcessWorkflowPipeline.Status.FAILED,
                pipeline.snapshot().get(1).status());
        assertEquals("could not save the snapshot", pipeline.snapshot().get(1).summary());
        assertEquals(tookB, pipeline.snapshot().get(1).elapsedMillis(),
                "the phase still took as long as it took");
    }

    /** A good ending is not propagated backwards: the phases already carry their
     *  verdicts, and the last one may have earned a PARTIAL. */
    @Test void successDoesNotOverwriteAPhaseThatEndedPartial() {
        ProcessWorkflowPipeline pipeline = pipeline();
        pipeline.start("a", "loading");
        pipeline.start("b", "materializing");
        pipeline.partial("b", "50 QIDs unavailable");

        pipeline.finish(ProcessStatus.SUCCEEDED, "done");

        assertEquals(ProcessWorkflowPipeline.Status.PARTIAL,
                pipeline.snapshot().get(1).status());
        assertEquals("50 QIDs unavailable", pipeline.snapshot().get(1).summary());
    }

    /** Nothing ran, so there is no phase to blame — the graph stays untouched. */
    @Test void aFailureBeforeAnyPhaseStartedMarksNothing() {
        ProcessWorkflowPipeline pipeline = pipeline();

        pipeline.finish(ProcessStatus.FAILED, "the model would not compile");

        assertTrue(pipeline.snapshot().stream().allMatch(
                state -> state.status() == ProcessWorkflowPipeline.Status.PENDING));
    }

    @Test void failureMarksTheActuallyRunningPhase() {
        ProcessWorkflowPipeline pipeline = pipeline();
        pipeline.start("a", "loading");
        pipeline.finish(ProcessStatus.FAILED, "connection reset");

        assertEquals(ProcessWorkflowPipeline.Status.FAILED,
                pipeline.snapshot().get(0).status());
        assertEquals("connection reset", pipeline.snapshot().get(0).summary());
    }

    @Test void elapsedTimeAdvancesWhileRunningAndFreezesWhenFinished() throws Exception {
        ProcessWorkflowPipeline pipeline = pipeline();
        pipeline.start("a", "loading");
        Thread.sleep(20);

        long running = pipeline.snapshot().get(0).elapsedMillis();
        assertTrue(running > 0);

        pipeline.complete("a", "loaded");
        long finished = pipeline.snapshot().get(0).elapsedMillis();
        Thread.sleep(20);

        assertTrue(finished >= running);
        assertEquals(finished, pipeline.snapshot().get(0).elapsedMillis());
    }

    /**
     * A phase's verdict can arrive long after it stopped: a failed load is only known to
     * be unrepaired once the run assembles its quality summary, which is where
     * GenerateDomainQuery marks role-evidence and classification partial. The phase must
     * keep the time it took, not be charged with everything the run did afterwards.
     */
    @Test void aLateVerdictDoesNotChargeThePhaseWithTheRestOfTheRun() throws Exception {
        ProcessWorkflowPipeline pipeline = pipeline();
        pipeline.start("a", "loading");
        Thread.sleep(20);
        pipeline.start("b", "the rest of the run");
        long tookA = pipeline.snapshot().get(0).elapsedMillis();

        Thread.sleep(120);
        pipeline.partial("a", "50 QIDs unavailable");

        assertEquals(ProcessWorkflowPipeline.Status.PARTIAL,
                pipeline.snapshot().get(0).status());
        assertEquals(tookA, pipeline.snapshot().get(0).elapsedMillis(),
                "the partial verdict must not restate how long the phase ran");
    }
}
