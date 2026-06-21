package wikidata.explore.query.logical;

import wikidata.explore.extract.RuleTreeExtractor;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.GenerationRun;
import wikidata.explore.rule.RuleNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Expects a snapshot of the project model (see
 * GeneratedProjectModel#copy()) — execute() runs off the EDT and must
 * not share the live, editable model.
 */
public class GenerateInstancesQuery
        implements Query<GenerationRun> {
    private final GeneratedProjectModel projectModel;
    private final int depth;

    public GenerateInstancesQuery(
            GeneratedProjectModel projectModel,
            int depth) {

        this.projectModel = projectModel;
        this.depth = depth;
    }

    @Override
    public String purpose() {
        return "Generate instances";
    }

    @Override
    public String skeleton() {
        return "compile project model -> RuleTreeExtractor -> dynamic objects -> generated Quizable objects";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("Class", projectModel.rootClass().className());
        return p;
    }

    @Override
    public GenerationRun execute(QueryContext context)
            throws Exception {

        Map<String, String> stepParams = new LinkedHashMap<>();
        stepParams.put("qid", projectModel.rootClass()
                                          .instanceMapping()
                                          .sourceQid());
        stepParams.put("depth", String.valueOf(depth));

        GenerationPipeline pipeline = new GenerationPipeline();
        RuleNode plan = pipeline.plan(projectModel);

        return context.step(
                "Extract via SPARQL",
                "SPARQL",
                null,
                stepParams,
                step -> {
                    // The step's request is ONLY the root query — a single
                    // runnable SELECT, so "Open in query service" works. The
                    // child-edge templates were previously glued on with "\n\n",
                    // producing a multi-SELECT block WDQS rejects; log them as
                    // separate entries instead.
                    java.util.List<String> previews =
                            new RuleTreeExtractor(context.sparql())
                                    .previewQueries(plan, depth);
                    if (!previews.isEmpty()) {
                        step.request(previews.get(0));
                    }
                    for (int i = 1; i < previews.size(); i++) {
                        context.message("\n--- child-edge query template "
                                + i + " (one runnable SELECT) ---\n"
                                + previews.get(i) + "\n");
                    }

                    // Forward extraction progress (incl. each per-parent sub-
                    // query) to the log window instead of discarding it.
                    GenerationRun run =
                            pipeline.fullRun(
                                    projectModel,
                                    depth,
                                    context.sparql(),
                                    context::message);

                    step.summary(run.size() + " objects");
                    return run;
                });
    }

    @Override
    public int rowCount(GenerationRun result) {
        return result == null ? 0 : result.size();
    }

    @Override
    public String summary(GenerationRun result) {
        return rowCount(result) + " objects";
    }
}
