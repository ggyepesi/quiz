package wikidata.explore.build;

import dataset.DomainStorage;
import process.Process;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessPlan;
import process.ProcessWorkflowPipeline;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.Map;
import java.util.Objects;

/**
 * A project build as a process, so the desktop runs it through the same runner, log and
 * cancellation as every other long operation. The build itself is {@link ProjectBuild};
 * this adds nothing to it.
 *
 * <p>A build that ends anywhere but CURRENT is PARTIAL, not FAILED: its report — which
 * step stopped it and why, and what it already wrote — is the useful result.
 */
public final class BuildProcess implements Process<ProjectBuild.Report> {

    private final GeneratedProjectModel model;
    private final DomainStorage storage;
    private final Completion completion;
    private final ProcessWorkflowPipeline pipeline;

    /**
     * Work the launching editor needs done with a CURRENT build's saved files before the
     * result appears. It runs in the build's own worker as the last visible phase, so
     * reading a large snapshot never blocks the event thread while the window still says
     * Running, and the reader sees which files are being read.
     */
    public record Completion(String title, Step step) {
        public Completion {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(step, "step");
        }

        @FunctionalInterface
        public interface Step {
            /** @return the phase's completion summary */
            String run(ProjectBuild.Report report, ProcessContext context) throws Exception;
        }
    }

    static final String COMPLETION_PHASE = "build-completion";

    public BuildProcess(GeneratedProjectModel model, DomainStorage storage) {
        this(model, storage, null);
    }

    public BuildProcess(GeneratedProjectModel model, DomainStorage storage,
                        Completion completion) {
        this.model = Objects.requireNonNull(model, "model");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.completion = completion;
        this.pipeline = configuredPipeline(model, completion);
    }

    /** The saved build list, used unchanged by Plan, Running, QueryLog and Results. */
    public ProcessWorkflowPipeline pipeline() { return pipeline; }

    @Override public ProcessPlan plan() {
        return new ProcessPlan("Build " + model.name(),
                "Run the saved build of " + model.name() + ": "
                        + model.buildOperations().size() + " step(s), reusing every current output.",
                Map.of("project", model.name(),
                        "manifest", storage.buildManifestFile(model.name()).getPath()));
    }

    @Override public ProcessOutcome<ProjectBuild.Report> execute(ProcessContext context)
            throws Exception {
        pipeline.reset();
        ProjectBuild.Report report = ProjectBuild.run(
                model, storage, context.queries(), context::message,
                new PipelineObserver(model.name(), pipeline));
        String summary = "Build " + report.outcome() + "; manifest "
                + report.manifest().getPath();
        if (report.outcome() != ProjectBuild.State.CURRENT) {
            if (completion != null) {
                pipeline.cancel(COMPLETION_PHASE,
                        "NOT_REACHED — the build ended " + report.outcome());
            }
            return ProcessOutcome.partial(report, null, summary);
        }
        if (completion != null) {
            pipeline.start(COMPLETION_PHASE, "RUNNING — " + completion.title());
            try {
                pipeline.complete(COMPLETION_PHASE,
                        "RAN — " + completion.step().run(report, context));
            } catch (Exception failure) {
                // The build's own files are written and stay CURRENT; only bringing them
                // into the editor failed, so the report is kept and the reason named.
                String reason = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                pipeline.fail(COMPLETION_PHASE, "FAILED — " + reason);
                return ProcessOutcome.partial(report, failure, summary + "; "
                        + completion.title() + " failed: " + reason);
            }
        }
        return ProcessOutcome.succeeded(report, summary);
    }

    private static ProcessWorkflowPipeline configuredPipeline(
            GeneratedProjectModel model, Completion completion) {
        java.util.List<wikidata.explore.model.BuildOperation> operations =
                model.buildOperations();
        java.util.List<ProcessWorkflowPipeline.Phase> phases = new java.util.ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            var operation = operations.get(index);
            String description = operation.describe(model);
            phases.add(new ProcessWorkflowPipeline.Phase(
                    operation.declarationId(), (index + 1) + " · " + description,
                    "Saved build step " + (index + 1) + " of " + operations.size()
                            + " for " + model.name() + ".",
                    java.util.List.of("Project: " + model.name(),
                            "Operation: " + operation.kind().label())));
        }
        if (completion != null) {
            phases.add(new ProcessWorkflowPipeline.Phase(COMPLETION_PHASE,
                    (operations.size() + 1) + " · " + completion.title(),
                    "Runs after a CURRENT build of " + model.name()
                            + ", before its result is shown.",
                    java.util.List.of("Project: " + model.name())));
        }
        return new ProcessWorkflowPipeline(phases);
    }

    /** Projects the coordinator's structured events onto the root build's phases. A
     * dependency stays one timed phase; while its own build runs, that phase names the
     * exact upstream project and step currently executing. */
    private static final class PipelineObserver implements ProjectBuild.Observer {
        private final String rootProject;
        private final ProcessWorkflowPipeline pipeline;
        private String activeDependency = "";

        private PipelineObserver(String rootProject, ProcessWorkflowPipeline pipeline) {
            this.rootProject = rootProject;
            this.pipeline = pipeline;
        }

        @Override public void planned(String projectName,
                                      java.util.List<ProjectBuild.Step> steps) {
            if (rootProject.equals(projectName)) {
                for (ProjectBuild.Step step : steps) {
                    pipeline.progress(step.operation().declarationId(),
                            "PLANNED — " + step.explain());
                }
            } else {
                dependencyProgress(projectName + " — plan: " + steps.size() + " step(s)");
            }
        }

        @Override public void started(String projectName, ProjectBuild.Step step) {
            if (rootProject.equals(projectName)) {
                String id = step.operation().declarationId();
                pipeline.start(id, "RUNNING — " + step.explain());
                activeDependency = step.operation().kind()
                        == wikidata.explore.model.BuildOperation.Kind.BUILD_DEPENDENCY
                        ? id : "";
            } else {
                dependencyProgress(projectName + " — running " + step.description());
            }
        }

        @Override public void finished(String projectName, ProjectBuild.Done step,
                                       ProjectBuild.State projectState) {
            if (!rootProject.equals(projectName)) {
                dependencyProgress(projectName + " — " + step.action() + " "
                        + step.planned().description()
                        + detail(step.detail()));
                return;
            }
            String id = step.planned().operation().declarationId();
            String summary = step.action() + detail(step.detail());
            switch (step.action()) {
                case RAN, REUSED -> pipeline.complete(id, summary);
                case STOPPED -> {
                    if (projectState == ProjectBuild.State.FAILED) {
                        pipeline.fail(id, summary);
                    } else {
                        pipeline.partial(id, summary);
                    }
                }
                case NOT_REACHED -> pipeline.cancel(id, summary);
            }
            if (id.equals(activeDependency)) activeDependency = "";
        }

        private void dependencyProgress(String summary) {
            if (!activeDependency.isBlank()) pipeline.progress(activeDependency, summary);
        }

        private static String detail(String value) {
            return value == null || value.isBlank() ? "" : " — " + value;
        }
    }
}
