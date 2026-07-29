package wikidata.explore.generation;

import objectview.Viewable;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.result.ObjectQueryResult;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
        GeneratedViewableRuntime runtime,
        List<Viewable> instances,
        RemapState remapState) {

    /** Back-compat: a run with no cached transform inputs (remap = display-only). */
    public GenerationRun(GeneratedProjectModel modelSnapshot, int depth, RuleNode plan,
                         List<WikidataDynamicObject> dynamicObjects,
                         GeneratedViewableRuntime runtime, List<Viewable> instances) {
        this(modelSnapshot, depth, plan, dynamicObjects, runtime, instances, null);
    }

    /**
     * The cached inputs a domain Remap re-transforms offline: the ENRICHED pool
     * (post qualifier-load, pre-reify — a deep copy, since the transforms mutate)
     * and the companion-match sets (so `won` re-computes without re-fetching P166).
     */
    public record RemapState(
            List<WikidataDynamicObject> enrichedPool,
            Map<String, Set<List<String>>> companionSets) {}

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
