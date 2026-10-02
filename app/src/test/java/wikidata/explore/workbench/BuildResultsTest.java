package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import process.ProcessOutcome;
import quiz.transform.DynamicViewable;
import wikidata.explore.build.ProjectBuild;
import wikidata.explore.model.BuildOperation;
import wikidata.explore.model.GeneratedProjectModel;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Build action's results say what each step did and why, and how to go on from a stop. */
class BuildResultsTest {

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

        var results = ModelBuilderFrame.buildResults(project,
                ProcessOutcome.partial(report, null, "stopped"));

        assertEquals("Load built instances", results.applyVerb());
        var card = (DynamicViewable) results.tabs().getFirst().cards().getFirst().view();
        assertEquals("RAN", card.get("Done"));
        assertEquals("STALE", card.get("State before"));
        assertEquals("the model changed since the snapshot was generated", card.get("Because"));
        assertTrue(results.summary().contains("accept or reject the Review entries"),
                results.summary());
        assertTrue(results.summary().contains("offices.build.json"));
        assertEquals(1, results.tabs().get(1).cards().size(), "every file written is listed");
    }
}
