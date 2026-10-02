package wikidata.explore.build;

import dataset.DomainStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessStatus;
import wikidata.explore.model.BuildOperation;
import wikidata.explore.model.GeneratedProjectModel;
import work.CancellationToken;
import work.QueryContext;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The desktop runs a build as a process, through the same runner as every long operation.
 * A build that stops is PARTIAL with its report, because the report — which step stopped
 * it and why — is what the reader needs; FAILED would carry no result at all.
 */
class BuildProcessTest {

    @TempDir Path root;

    @Test void aCurrentBuildSucceeds() throws Exception {
        ProcessOutcome<ProjectBuild.Report> outcome = run(
                new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, ""));

        assertEquals(ProcessStatus.SUCCEEDED, outcome.status());
        assertEquals(ProjectBuild.State.CURRENT, outcome.result().outcome());
    }

    @Test void aStoppedBuildIsPartialAndKeepsItsReport() throws Exception {
        ProcessOutcome<ProjectBuild.Report> outcome = run(
                new BuildOperation(BuildOperation.Kind.RUN_GRAPH_CONSTRAINT, "no-such-graph"),
                new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, ""));

        assertEquals(ProcessStatus.PARTIAL, outcome.status());
        assertEquals(ProjectBuild.State.FAILED, outcome.result().outcome());
        assertEquals(List.of(ProjectBuild.Action.STOPPED, ProjectBuild.Action.NOT_REACHED),
                outcome.result().steps().stream().map(ProjectBuild.Done::action).toList());
        assertTrue(outcome.summary().contains(outcome.result().manifest().getPath()));
    }

    private ProcessOutcome<ProjectBuild.Report> run(BuildOperation... build) throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Stars");
        model.rootClass().className("Star");
        model.buildOperations(List.of(build));
        return new BuildProcess(model, DomainStorage.in(root.toFile())).execute(
                new ProcessContext(new QueryContext(), null, null, new CancellationToken(), null));
    }
}
