package wikidata.explore.generation;

import process.Process;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessPlan;
import process.QuerySubprocess;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.logical.GenerateDomainQuery;

import java.util.List;
import java.util.Map;

/** Process façade for domain generation; the established Query is an explicit subprocess. */
public final class GenerateDomainProcess implements Process<GenerationRun> {
    private final GeneratedProjectModel project;
    private final ProcessPlan plan;

    public GenerateDomainProcess(GeneratedProjectModel project) {
        this.project = project.copy();
        this.plan = new ProcessPlan(
                "Generate domain",
                "Generate every configured class into one shared domain snapshot",
                Map.of("domain", this.project.name(),
                        "classes", Integer.toString(this.project.classes().size())),
                List.of(
                        phase("Plan and extract roots",
                                "Compile the model and download each root population"),
                        phase("Reify statements",
                                "Load statement qualifiers and create statement-class records"),
                        phase("Load role evidence",
                                "Load declared fields on referenced role members"),
                        phase("Classify entity kinds",
                                "Reuse stored evidence, fetching only evidence that is absent"),
                        phase("Build owned components",
                                "Create owned values after their owner kinds are settled"),
                        phase("Load kind and owned fields",
                                "Fill newly reachable declarations without repeating completed work"),
                        phase("Validate and materialize",
                                "Prune invalid objects, build vocabularies, and map the final snapshot")));
    }

    @Override public ProcessPlan plan() {
        return plan;
    }

    @Override public ProcessOutcome<GenerationRun> execute(ProcessContext context) {
        ProcessOutcome<GenerationRun> outcome =
                context.run(new QuerySubprocess<>(new GenerateDomainQuery(project)));
        GenerationRun run = outcome.result();
        if (outcome.status() == process.ProcessStatus.SUCCEEDED
                && run != null && !run.quality().complete()) {
            return ProcessOutcome.partial(run, null,
                    outcome.summary() + " — partial: "
                            + String.join("; ", run.quality().warnings()));
        }
        return outcome;
    }

    private static ProcessPlan phase(String title, String description) {
        return new ProcessPlan(title, description, Map.of());
    }
}
