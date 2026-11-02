package wikidata.explore.build;

import dataset.DomainStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessStatus;
import process.ProcessWorkflowPipeline;
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
        BuildProcess process = process(
                new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, ""));
        ProcessOutcome<ProjectBuild.Report> outcome = run(process);

        assertEquals(ProcessStatus.SUCCEEDED, outcome.status());
        assertEquals(ProjectBuild.State.CURRENT, outcome.result().outcome());
        assertEquals(ProcessWorkflowPipeline.Status.COMPLETED,
                process.pipeline().snapshot().getFirst().status());
        assertEquals("REUSED — current",
                process.pipeline().snapshot().getFirst().summary());
    }

    @Test void aStoppedBuildIsPartialAndKeepsItsReport() throws Exception {
        BuildProcess process = process(
                new BuildOperation(BuildOperation.Kind.RUN_GRAPH_CONSTRAINT, "no-such-graph"),
                new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, ""));
        ProcessOutcome<ProjectBuild.Report> outcome = run(process);

        assertEquals(ProcessStatus.PARTIAL, outcome.status());
        assertEquals(ProjectBuild.State.FAILED, outcome.result().outcome());
        assertEquals(List.of(ProjectBuild.Action.STOPPED, ProjectBuild.Action.NOT_REACHED),
                outcome.result().steps().stream().map(ProjectBuild.Done::action).toList());
        assertTrue(outcome.summary().contains(outcome.result().manifest().getPath()));
        assertEquals(List.of(ProcessWorkflowPipeline.Status.FAILED,
                        ProcessWorkflowPipeline.Status.CANCELLED),
                process.pipeline().snapshot().stream().map(
                        ProcessWorkflowPipeline.PhaseState::status).toList());
        assertTrue(process.pipeline().snapshot().getFirst().summary().startsWith("STOPPED"));
        assertTrue(process.pipeline().snapshot().get(1).summary().startsWith("NOT_REACHED"));
    }

    @Test void theLivePipelineIsTheSavedBuildListInOrder() {
        BuildProcess process = process(
                new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, ""),
                new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, ""));

        assertEquals(List.of("1 · Generate project", "2 · Save project"),
                process.pipeline().snapshot().stream()
                        .map(state -> state.phase().title()).toList());
    }

    @Test void repeatedOperationsRemainDistinctInTheVisiblePipeline() {
        BuildProcess process = process(
                new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, ""),
                new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, ""));

        assertEquals(List.of("1 · Generate project", "2 · Generate project"),
                process.pipeline().snapshot().stream()
                        .map(state -> state.phase().title()).toList());
    }

    @Test void aCurrentBuildRunsItsCompletionAsTheLastVisiblePhase() throws Exception {
        List<ProjectBuild.State> seen = new java.util.ArrayList<>();
        BuildProcess process = process(new BuildProcess.Completion("Load into editor",
                        (report, context) -> {
                            seen.add(report.outcome());
                            return "loaded";
                        }),
                new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, ""));

        ProcessOutcome<ProjectBuild.Report> outcome = run(process);

        assertEquals(ProcessStatus.SUCCEEDED, outcome.status());
        assertEquals(List.of(ProjectBuild.State.CURRENT), seen);
        ProcessWorkflowPipeline.PhaseState last = process.pipeline().snapshot().getLast();
        assertEquals("2 · Load into editor", last.phase().title());
        assertEquals(ProcessWorkflowPipeline.Status.COMPLETED, last.status());
        assertEquals("RAN — loaded", last.summary());
    }

    @Test void aStoppedBuildNeverReachesItsCompletion() throws Exception {
        List<ProjectBuild.State> seen = new java.util.ArrayList<>();
        BuildProcess process = process(new BuildProcess.Completion("Load into editor",
                        (report, context) -> {
                            seen.add(report.outcome());
                            return "loaded";
                        }),
                new BuildOperation(BuildOperation.Kind.RUN_GRAPH_CONSTRAINT, "no-such-graph"));

        run(process);

        assertTrue(seen.isEmpty());
        assertEquals(ProcessWorkflowPipeline.Status.CANCELLED,
                process.pipeline().snapshot().getLast().status());
    }

    @Test void aFailedCompletionKeepsTheCurrentReportAndNamesWhy() throws Exception {
        BuildProcess process = process(new BuildProcess.Completion("Load into editor",
                        (report, context) -> {
                            throw new java.io.IOException("no snapshot at x.json");
                        }),
                new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, ""));

        ProcessOutcome<ProjectBuild.Report> outcome = run(process);

        assertEquals(ProcessStatus.PARTIAL, outcome.status());
        assertEquals(ProjectBuild.State.CURRENT, outcome.result().outcome());
        assertTrue(outcome.summary().contains("no snapshot at x.json"));
        ProcessWorkflowPipeline.PhaseState last = process.pipeline().snapshot().getLast();
        assertEquals(ProcessWorkflowPipeline.Status.FAILED, last.status());
        assertEquals("FAILED — no snapshot at x.json", last.summary());
    }

    private BuildProcess process(BuildOperation... build) {
        return process(null, build);
    }

    private BuildProcess process(BuildProcess.Completion completion, BuildOperation... build) {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Stars");
        model.rootClass().className("Star");
        model.buildOperations(List.of(build));
        return new BuildProcess(model, DomainStorage.in(root.toFile()), completion);
    }

    private static ProcessOutcome<ProjectBuild.Report> run(BuildProcess process)
            throws Exception {
        return process.execute(
                new ProcessContext(new QueryContext(), null, null, new CancellationToken(), null));
    }
}
