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

    public RemapInstancesQuery(
            GenerationRun previousRun,
            GeneratedProjectModel projectModel) {

        this.previousRun = previousRun;
        this.projectModel = projectModel;
    }

    @Override
    public String purpose() {
        return "Remap instances (no download)";
    }

    @Override
    public String skeleton() {
        return "reuse downloaded dynamic objects -> recompile generated class -> remap";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("rootClass", projectModel.rootClass().className());
        p.put("reusedObjects",
              String.valueOf(previousRun.dynamicObjects().size()));
        p.put("depth", String.valueOf(previousRun.depth()));
        return p;
    }

    @Override
    public GenerationRun execute(QueryContext context)
            throws Exception {

        context.message("Reusing "
                                + previousRun.dynamicObjects().size()
                                + " downloaded objects from the previous run.");

        return new GenerationPipeline().remap(
                previousRun, projectModel,
                wikidata.explore.extract.GenerationLog.of(context::message));
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
