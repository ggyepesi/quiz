package domain.workbench.execution.ui;

import dataset.DomainStorage;
import domain.build.BuildProcess;
import domain.build.ProjectBuild;
import domain.config.GeneratedProjectModel;
import domain.persistence.ProjectSaveGuard;
import domain.transform.DynamicViewable;
import objectview.Viewable;
import process.Process;
import process.ProcessOutcome;
import process.ProcessWorkflowPipeline;
import process.swing.workflow.ProcessWorkflowAction;
import process.swing.workflow.ProcessWorkflowPlan;
import process.swing.workflow.ProcessWorkflowResults;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** The saved build's shared plan, process and inspection-only report. */
public final class BuildProjectWorkflow {
    private BuildProjectWorkflow() { }

    // The build report's cards show exactly these fields (#363).
    static {
        objectview.field.RecordTypes.declare("Build step",
                objectview.field.RecordTypes.text("Done"),
                objectview.field.RecordTypes.text("State before"),
                objectview.field.RecordTypes.text("Because"),
                objectview.field.RecordTypes.text("Detail"));
        objectview.field.RecordTypes.declare("File", objectview.field.RecordTypes.text("Path"));
        objectview.field.RecordTypes.declare("Written");
    }

    public record Loaded<T>(T value, String summary) { }

    @FunctionalInterface
    public interface Reload<T> {
        Loaded<T> read(Consumer<String> log) throws Exception;
    }

    public static <T> ProcessWorkflowAction<ProjectBuild.Report, ProjectBuild.Report> action(
            GeneratedProjectModel saved, File modelFile, DomainStorage storage, ProjectSaveGuard guard,
            String reloadTitle, Reload<T> reload, Consumer<T> install, String inspection) {
        List<Viewable> steps = saved.buildOperations().stream()
                .map(operation -> (Viewable) card(operation.declarationId(), operation.describe(saved), "Build step"))
                .toList();
        List<Viewable> files = Stream.of(modelFile, storage.snapshotFile(saved.name()),
                        storage.constructManifestFile(saved.name()), storage.buildManifestFile(saved.name()))
                .map(file -> {
                    var card = card(file.getPath(), file.getName(), "File");
                    card.put("Path", file.getPath());
                    return (Viewable) card;
                }).toList();
        var loaded = new AtomicReference<Loaded<T>>();
        var build = new BuildProcess(saved, storage, guard, new BuildProcess.Completion(reloadTitle,
                (report, context) -> {
                    var result = reload.read(context::message);
                    loaded.set(result);
                    return result.summary();
                }));
        return new ProcessWorkflowAction<>() {
            @Override public String id() { return "build-project"; }
            @Override public ProcessWorkflowPipeline pipeline() { return build.pipeline(); }
            @Override public Process<ProjectBuild.Report> process() { return build; }
            @Override public ProcessWorkflowPlan plan() {
                return new ProcessWorkflowPlan("Build " + saved.name(),
                        "Run the saved build of " + modelFile.getPath() + ". The build reuses current "
                                + "outputs and runs the remaining steps in order. A graph awaiting review, "
                                + "incomplete generation or a save warning stops the build. Report: "
                                + storage.buildManifestFile(saved.name()).getPath(),
                        List.of(new ProcessWorkflowPlan.Tab("Steps", steps),
                                new ProcessWorkflowPlan.Tab("Files it may read or write", files)));
            }
            @Override public void afterCompletion(ProcessOutcome<ProjectBuild.Report> outcome) {
                var completed = loaded.getAndSet(null);
                if (completed != null) install.accept(completed.value());
            }
            @Override public ProcessWorkflowResults<ProjectBuild.Report> results(
                    ProcessOutcome<ProjectBuild.Report> outcome) {
                return BuildProjectWorkflow.results(saved, outcome, inspection);
            }
            @Override public void apply(List<ProjectBuild.Report> ignored) { }
        };
    }

    private static DynamicViewable card(String id, String title, String type) {
        var card = new DynamicViewable(id, title);
        card.type(type);
        return card;
    }

    /** The build's report as result tabs: every step as the build left it, and every file
     *  it wrote. */
    public static ProcessWorkflowResults<ProjectBuild.Report>
            results(GeneratedProjectModel project,
                    ProcessOutcome<ProjectBuild.Report> outcome, String inspection) {
        ProjectBuild.Report report = outcome.result();
        if (report == null) {
            return new ProcessWorkflowResults<>(
                    "Build " + project.name() + " — failed",
                    outcome.error() == null ? outcome.summary() : outcome.error().getMessage(),
                    List.of());
        }
        List<ProcessWorkflowResults.Card<ProjectBuild.Report>> steps = new ArrayList<>();
        for (ProjectBuild.Done done : report.steps()) {
            var card = card(done.planned().operation().declarationId(), done.planned().description(), "Build step");
            card.put("Done", done.action().name());
            card.put("State before", done.planned().state().name());
            if (!done.planned().because().isEmpty()) {
                card.put("Because", String.join("; ", done.planned().because()));
            }
            if (!done.detail().isBlank()) card.put("Detail", done.detail());
            steps.add(new ProcessWorkflowResults.Card<>(card, null, false));
        }
        List<ProcessWorkflowResults.Card<ProjectBuild.Report>> files = new ArrayList<>();
        for (String line : report.files()) {
            files.add(new ProcessWorkflowResults.Card<>(card(line, line, "Written"), null, false));
        }
        String summary = switch (report.outcome()) {
            case CURRENT -> outcome.error() == null
                    ? "Build completed and saved. " + inspection
                    : "Build completed and saved. Opening the built project failed: "
                            + outcome.error().getMessage();
            case AWAITING_DECISION -> "Stopped: a graph result is awaiting a decision, and the "
                    + "snapshot was not replaced. In ModelBuilder, load the instances, accept or reject "
                    + "the Review entries named in the steps on that graph's tab, save, and "
                    + "build again.";
            case INCOMPLETE -> "Stopped: generation was incomplete, so nothing was saved.";
            default -> report.steps().stream().filter(step -> step.action() == ProjectBuild.Action.STOPPED)
                    .findFirst().map(step -> "Stopped at " + step.planned().description() + ". " + step.detail())
                    .orElse("Build stopped before completion.");
        } + " Manifest: " + report.manifest().getPath();
        return new ProcessWorkflowResults<>(
                "Build " + project.name() + " — " + report.outcome(), summary,
                "Build already saved",
                List.of(
                        new ProcessWorkflowResults.Tab<>("Steps", steps),
                        new ProcessWorkflowResults.Tab<>("Files written", files)),
                null, report.outcome() == ProjectBuild.State.CURRENT
                        ? "Close — build already saved" : "Close");
    }

}
