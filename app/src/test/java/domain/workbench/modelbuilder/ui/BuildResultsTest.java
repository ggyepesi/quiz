package domain.workbench.modelbuilder.ui;

import org.junit.jupiter.api.Test;
import process.ProcessOutcome;
import domain.transform.DynamicViewable;
import domain.build.ProjectBuild;
import domain.config.BuildOperation;
import domain.config.GeneratedProjectModel;
import domain.workbench.execution.ui.BuildProjectWorkflow;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Build action's results say what each step did and why, and how to go on from a stop. */
class BuildResultsTest {

    @Test void currentBuildSaysItIsAlreadySavedAndNeedsNoAcceptance() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("Offices");
        ProjectBuild.Report report = new ProjectBuild.Report(
                List.of(), ProjectBuild.State.CURRENT, List.of(),
                new File("offices.build.json"));

        var results = BuildProjectWorkflow.results(project,
                ProcessOutcome.succeeded(report, "built"), "Use Show instances to inspect it.");

        assertNull(results.resultDecision());
        assertTrue(results.summary().contains("completed and saved"), results.summary());
        assertEquals("Close — build already saved", results.closeVerb());
    }

    @Test void eachStepSaysWhatWasDoneAndWhy() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("Offices");
        ProjectBuild.Step generate = new ProjectBuild.Step(
                new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, ""),
                "Generate project", ProjectBuild.State.STALE,
                List.of("the model changed since the snapshot was generated"));
        ProjectBuild.Report report = new ProjectBuild.Report(
                List.of(new ProjectBuild.Done(generate, ProjectBuild.Action.RAN, "12 objects")),
                ProjectBuild.State.AWAITING_DECISION, List.of("Instances: 12 -> offices.snapshot.json"),
                new File("offices.build.json"));

        var results = BuildProjectWorkflow.results(project,
                ProcessOutcome.partial(report, null, "stopped"), "Use Show instances to inspect it.");

        assertEquals("Build already saved", results.applyVerb());
        assertNull(results.resultDecision(),
                "a completed build is inspection-only; it must not need Apply to synchronize");
        var card = (DynamicViewable) results.tabs().getFirst().cards().getFirst().view();
        assertNull(results.tabs().getFirst().cards().getFirst().decision().get(),
                "selecting a build step must not offer Apply");
        assertEquals("RAN", card.get("Done"));
        assertEquals("STALE", card.get("State before"));
        assertEquals("the model changed since the snapshot was generated", card.get("Because"));
        assertTrue(results.summary().contains("accept or reject the Review entries"),
                results.summary());
        assertTrue(results.summary().contains("offices.build.json"));
        assertEquals(1, results.tabs().get(1).cards().size(), "every file written is listed");
    }
}
