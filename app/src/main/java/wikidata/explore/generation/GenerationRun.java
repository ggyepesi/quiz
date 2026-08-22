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
        RemapState remapState,
        List<wikidata.explore.extract.LoadedDeclaration> loadedDeclarations,
        Quality quality,
        List<wikidata.explore.transform.FieldExpectations.FieldCoverage> fieldCoverage) {

    public GenerationRun {
        loadedDeclarations = loadedDeclarations == null
                ? List.of() : List.copyOf(loadedDeclarations);
        quality = quality == null ? Quality.completeQuality() : quality;
        fieldCoverage = fieldCoverage == null ? List.of() : List.copyOf(fieldCoverage);
    }

    /** Compatibility constructor for callers that have quality but no finalization report. */
    public GenerationRun(GeneratedProjectModel modelSnapshot, int depth, RuleNode plan,
                         List<WikidataDynamicObject> dynamicObjects,
                         GeneratedViewableRuntime runtime, List<Viewable> instances,
                         RemapState remapState,
                         List<wikidata.explore.extract.LoadedDeclaration> loadedDeclarations,
                         Quality quality) {
        this(modelSnapshot, depth, plan, dynamicObjects, runtime, instances, remapState,
                loadedDeclarations, quality, List.of());
    }

    /** Compatibility constructor for local/remap paths that produced a complete run. */
    public GenerationRun(GeneratedProjectModel modelSnapshot, int depth, RuleNode plan,
                         List<WikidataDynamicObject> dynamicObjects,
                         GeneratedViewableRuntime runtime, List<Viewable> instances,
                         RemapState remapState,
                         List<wikidata.explore.extract.LoadedDeclaration> loadedDeclarations) {
        this(modelSnapshot, depth, plan, dynamicObjects, runtime, instances,
                remapState, loadedDeclarations, Quality.completeQuality(), List.of());
    }

    /** Back-compat: a run with no cached transform inputs (remap = display-only). */
    public GenerationRun(GeneratedProjectModel modelSnapshot, int depth, RuleNode plan,
                         List<WikidataDynamicObject> dynamicObjects,
                         GeneratedViewableRuntime runtime, List<Viewable> instances) {
        this(modelSnapshot, depth, plan, dynamicObjects, runtime, instances, null);
    }

    /** Which declarations have been fetched is carried by the run, so a save records it
     *  and the next enrich asks only for what is new. */
    public GenerationRun(GeneratedProjectModel modelSnapshot, int depth, RuleNode plan,
                         List<WikidataDynamicObject> dynamicObjects,
                         GeneratedViewableRuntime runtime, List<Viewable> instances,
                         RemapState remapState) {
        this(modelSnapshot, depth, plan, dynamicObjects, runtime, instances,
                remapState, List.of());
    }

    /** Explicit data-completeness contract. A partial download is usable for review,
     * but can no longer masquerade as a complete generated domain. */
    public record Quality(
            boolean complete,
            List<String> warnings,
            List<String> unavailableQids) {
        public Quality {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            unavailableQids = unavailableQids == null
                    ? List.of() : unavailableQids.stream().distinct().toList();
        }

        public static Quality completeQuality() {
            return new Quality(true, List.of(), List.of());
        }

        public static Quality partial(List<String> warnings, List<String> qids) {
            return new Quality(false, warnings, qids);
        }
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
