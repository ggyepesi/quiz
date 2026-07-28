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
                List.of(new ProcessPlan(
                        "Download and transform domain",
                        "Existing generation query adapted as a subprocess",
                        Map.of())));
    }

    @Override public ProcessPlan plan() {
        return plan;
    }

    @Override public ProcessOutcome<GenerationRun> execute(ProcessContext context) {
        return context.run(new QuerySubprocess<>(new GenerateDomainQuery(project)));
    }
}
