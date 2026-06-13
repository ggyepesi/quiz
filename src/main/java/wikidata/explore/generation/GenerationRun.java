package wikidata.explore.generation;

import quiz.Quizable;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.result.ObjectQueryResult;
import wikidata.explore.codegen.GeneratedQuizableRuntime;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

/**
 * Everything one generation produced, end to end: the model snapshot it
 * ran against, the compiled rule-tree plan, the downloaded dynamic
 * objects, the compiled runtime and the mapped instances. Inspection
 * features (generated source, SPARQL preview, remapping) should read
 * from here instead of keeping their own copies.
 */
public record GenerationRun(
        GeneratedProjectModel modelSnapshot,
        int depth,
        RuleNode plan,
        List<WikidataDynamicObject> dynamicObjects,
        GeneratedQuizableRuntime runtime,
        List<Quizable> instances) {

    public int size() {
        return instances == null ? 0 : instances.size();
    }

    public ObjectQueryResult objectResult() {
        return new ObjectQueryResult(
                instances,
                runtime.generatedClass(),
                runtime.source());
    }
}
