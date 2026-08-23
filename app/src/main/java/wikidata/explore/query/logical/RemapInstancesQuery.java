package wikidata.explore.query.logical;

import wikidata.explore.model.GeneratedProjectModel;
import work.Query;
import work.QueryContext;
import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.GenerationRun;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Re-runs only the local stages (recompile generated class, remap) on a
 * previous run's downloaded dynamic objects. Only valid when
 * GenerationPipeline#sameExtraction holds for the snapshot — the caller
 * checks that before constructing this query.
 */
public class RemapInstancesQuery
        implements Query<GenerationRun> {

    private final GenerationRun previousRun;
    private final GeneratedProjectModel projectModel;
    private final wikidata.explore.generation.RunSteps steps;

    public RemapInstancesQuery(
            GenerationRun previousRun,
            GeneratedProjectModel projectModel) {
        this(previousRun, projectModel, wikidata.explore.generation.RunSteps.SILENT);
    }

    /** Reporting each step it finishes, so the plan's steps are the run's steps. */
    public RemapInstancesQuery(
            GenerationRun previousRun,
            GeneratedProjectModel projectModel,
            wikidata.explore.generation.RunSteps steps) {

        this.previousRun = previousRun;
        this.projectModel = projectModel;
        this.steps = steps == null
                ? wikidata.explore.generation.RunSteps.SILENT : steps;
    }

    @Override
    public String purpose() {
        return "Remap instances (no download)";
    }

    @Override
    public String skeleton() {
        return "reuse downloaded dynamic objects -> recompile generated class -> remap";
    }

    /** What this Remap can actually apply — asked BEFORE it runs, so the plan can say
     *  which stages will not re-run rather than reporting a success that skipped them. */
    public wikidata.explore.generation.RemapScope scope() {
        return wikidata.explore.generation.RemapScope.of(previousRun);
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("rootClass", projectModel.rootClass().className());
        p.put("reusedObjects",
              String.valueOf(previousRun.dynamicObjects().size()));
        p.put("depth", String.valueOf(previousRun.depth()));
        p.put("scope", scope().describe());
        return p;
    }

    @Override
    public GenerationRun execute(QueryContext context)
            throws Exception {

        context.message("Reusing "
                                + previousRun.dynamicObjects().size()
                                + " downloaded objects from the previous run.");
        String limitation = scope().limitation();
        if (!limitation.isEmpty()) {
            context.message(limitation);
        }

        return new GenerationPipeline().remap(
                previousRun, projectModel,
                wikidata.explore.extract.GenerationLog.of(context::message), steps);
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
