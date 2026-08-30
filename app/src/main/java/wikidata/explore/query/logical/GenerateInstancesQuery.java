package wikidata.explore.query.logical;

import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.core.Datasource;
import work.Query;
import work.QueryContext;
import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.GenerationRun;

import java.util.LinkedHashMap;
import java.util.Map;
import wikidata.explore.query.core.WikidataAccess;

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
        return "Generate class instances";
    }

    @Override
    public String skeleton() {
        return "compile project model -> RuleTreeExtractor -> dynamic objects -> generated Viewable objects";
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

        datasource.api.SourceExecutionPlan sourcePlan =
                wikidata.explore.model.ModelSourceExecutionPlan.synchronizeAndCompile(
                        projectModel, datasource.Datasources.standard());
        context.message(wikidata.explore.model.ModelSourceExecutionPlan.generationMessage(
                sourcePlan));

        Map<String, String> stepParams = new LinkedHashMap<>();
        stepParams.put("qid", projectModel.rootClass()
                                          .instanceMapping()
                                          .sourceQid());
        stepParams.put("depth", String.valueOf(depth));

        GenerationPipeline pipeline = new GenerationPipeline();

        return context.step(
                "Extract via SPARQL",
                "Extract",   // container node; the real SPARQL is in the child subqueries
                null,
                stepParams,
                step -> {
                    // The step's request is a one-line note; the actual queries
                    // (root + each per-parent child) are recorded as structured,
                    // collapsible SUB-QUERIES under this step rather than appended
                    // to one ever-growing request blob.
                    step.request("Extraction — see sub-queries below.");

                    // message -> step request text; subquery -> a child log node.
                    wikidata.explore.extract.GenerationLog genLog =
                            new wikidata.explore.extract.GenerationLog() {
                                @Override public void message(String text) {
                                    context.message(text);
                                }
                                @Override public void subquery(
                                        String title, String request, String summary) {
                                    step.subquery(title, request, summary);
                                }
                            };
                    // Every endpoint this run can reach reports its requests, and their
                    // timings, into THIS run's log.
                    try (WikidataAccess.RequestLogs requestLogs =
                            WikidataAccess.logRequests(context, genLog::message)) {

                    GenerationRun run =
                            pipeline.fullRun(
                                    projectModel,
                                    depth,
                                    WikidataAccess.sparql(context, Datasource.WIKIDATA),
                                    genLog,
                                    WikidataAccess.api(context),
                                    context.cancellation(),
                                    sourcePlan,
                                    WikidataAccess.sparql(context, Datasource.DBPEDIA));

                    step.summary(run.size() + " objects");
                    return run;
                    }
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
