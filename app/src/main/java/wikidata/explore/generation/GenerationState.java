package wikidata.explore.generation;

import wikidata.api.WikidataFactStore;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.extract.LoadedDeclaration;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable state staged by one generation workflow; never published before Apply. */
public final class GenerationState {
    private final GeneratedProjectModel model;
    private final CompiledProjectModel compiledModel;
    private final WikidataFactStore facts;
    private final GenerationQualityTracker quality = new GenerationQualityTracker();
    private final List<WikidataDynamicObject> pool = new ArrayList<>();
    private final Map<String, LoadedDeclaration> loadedDeclarations = new LinkedHashMap<>();

    public GenerationState(GeneratedProjectModel model, CompiledProjectModel compiledModel,
                           WikidataFactStore facts) {
        this.model = java.util.Objects.requireNonNull(model, "model");
        this.compiledModel = java.util.Objects.requireNonNull(compiledModel, "compiledModel");
        this.facts = facts == null ? new WikidataFactStore() : facts;
    }

    public GeneratedProjectModel model() { return model; }
    public CompiledProjectModel compiledModel() { return compiledModel; }
    public WikidataFactStore facts() { return facts; }
    public GenerationQualityTracker quality() { return quality; }
    public List<WikidataDynamicObject> pool() { return pool; }
    public Map<String, LoadedDeclaration> loadedDeclarations() { return loadedDeclarations; }
}
