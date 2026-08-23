package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import process.ProcessWorkflowPipeline;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remap ran as one opaque box called "Remap locally", so its plan could not say what the
 * operation was about to do and its result had nowhere to hang what each part of the
 * work accounted for. It runs the same steps a generation does — construct, settle
 * semantics, finalize — and now reports them.
 */
class RunStepsTest {

    private static ProcessWorkflowPipeline threeStep() {
        return new ProcessWorkflowPipeline(List.of(
                new ProcessWorkflowPipeline.Phase(
                        GenerateDomainPipeline.CONSTRUCT, "Construct", "", List.of()),
                new ProcessWorkflowPipeline.Phase(
                        GenerateDomainPipeline.SEMANTIC, "Semantic", "", List.of()),
                new ProcessWorkflowPipeline.Phase(
                        GenerateDomainPipeline.FINALIZE, "Finalize", "", List.of())));
    }

    @Test void eachStepReportsWhatItProduced() {
        ProcessWorkflowPipeline pipeline = threeStep();
        RunSteps steps = RunSteps.of(pipeline);

        steps.completed(GenerateDomainPipeline.CONSTRUCT, "14904 statement record(s)");
        steps.completed(GenerateDomainPipeline.SEMANTIC, "0 kind(s), 0 owned part(s)");

        assertEquals("14904 statement record(s)",
                pipeline.snapshot().getFirst().summary());
        assertEquals(ProcessWorkflowPipeline.Status.COMPLETED,
                pipeline.snapshot().getFirst().status());
        assertEquals(ProcessWorkflowPipeline.Status.PENDING,
                pipeline.snapshot().get(2).status(),
                "a step that has not run yet is not marked done by its neighbours");
    }

    @Test void aStepThePlanDoesNotDeclareIsNotInvented() {
        // Enrich still runs as a single step. Reporting a Construct phase into its plan
        // would put a stage there that the operation never ran — and would fail on a
        // node that does not exist.
        ProcessWorkflowPipeline single = new ProcessWorkflowPipeline(List.of(
                new ProcessWorkflowPipeline.Phase("enrich", "Enrich", "", List.of())));

        RunSteps.of(single).completed(GenerateDomainPipeline.CONSTRUCT, "something");

        assertEquals(1, single.snapshot().size());
        assertEquals(ProcessWorkflowPipeline.Status.PENDING,
                single.snapshot().getFirst().status());
    }

    @Test void reportingWithNothingListeningIsSilentRatherThanFatal() {
        // GenerationPipeline is also driven headlessly and from tests, where there is
        // no pipeline to report to.
        RunSteps.of(null).completed(GenerateDomainPipeline.CONSTRUCT, "anything");
        RunSteps.SILENT.completed(GenerateDomainPipeline.FINALIZE, "anything");
    }

    @Test void theStepsAreTheOnesARuleBucketNames() {
        // What makes a bucket's phase mean the same thing in either operation: the plan
        // a remap declares uses the ids the effects are attached to.
        ProcessWorkflowPipeline pipeline = threeStep();
        for (RuleEffects.RunPhase phase : RuleEffects.RunPhase.values()) {
            assertTrue(pipeline.snapshot().stream()
                            .anyMatch(s -> s.phase().id().equals(phase.pipelinePhaseId())),
                    phase + " has no step in a remap's plan to attach to");
        }
    }
}
