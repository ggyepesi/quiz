package wikidata.explore.query.logical;

import quiz.Quizable;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.tree.*;

import java.util.LinkedHashMap;
import java.util.List;
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
        p.put("rootClass", projectModel.rootClass().className());
        p.put("rootQid", projectModel.rootClass()
                                     .instanceMapping()
                                     .sourceQid());
        p.put("depth", String.valueOf(depth));
        return p;
    }

    @Override
    public GenerationRun execute(QueryContext context)
            throws Exception {

        RuleNode root =
                RuleTreeCompiler.compileProject(projectModel);

        RuleTreeExtractor extractor =
                new RuleTreeExtractor(context.sparql());

        List<WikidataDynamicObject> dynamicObjects =
                extractor.load(root, depth, context::logText);

        GeneratedQuizableRuntime runtime =
                new GeneratedQuizableRuntimeBuilder()
                        .build(projectModel.rootClass());

        List<Quizable> generatedObjects =
                new GeneratedQuizableMapper(runtime)
                        .mapRoots(dynamicObjects);

        return new GenerationRun(
                projectModel,
                depth,
                root,
                dynamicObjects,
                runtime,
                generatedObjects);
    }

    @Override
    public int rowCount(GenerationRun result) {
        return result == null ? 0 : result.size();
    }
}
