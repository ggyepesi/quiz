package wikidata.explore.build;

import dataset.DomainStorage;
import process.Process;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessPlan;
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

    public BuildProcess(GeneratedProjectModel model, DomainStorage storage) {
        this.model = Objects.requireNonNull(model, "model");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override public ProcessPlan plan() {
        return new ProcessPlan("Build " + model.name(),
                "Run the saved build of " + model.name() + ": "
                        + model.buildOperations().size() + " step(s), reusing every current output.",
                Map.of("project", model.name(),
                        "manifest", storage.buildManifestFile(model.name()).getPath()));
    }

    @Override public ProcessOutcome<ProjectBuild.Report> execute(ProcessContext context)
            throws Exception {
        ProjectBuild.Report report = ProjectBuild.run(
                model, storage, context.queries(), context::message);
        String summary = "Build " + report.outcome() + "; manifest "
                + report.manifest().getPath();
        return report.outcome() == ProjectBuild.State.CURRENT
                ? ProcessOutcome.succeeded(report, summary)
                : ProcessOutcome.partial(report, null, summary);
    }
}
