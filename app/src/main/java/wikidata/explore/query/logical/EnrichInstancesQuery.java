package wikidata.explore.query.logical;

import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.GenerationRun;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads DECLARED fields that were never fetched, over the entities already downloaded.
 *
 * <p>Between Generate and Remap. Declaring a field on a referenced-only or owned class —
 * {@code Nominee.type}, {@code Name.familyName} — changes no membership and invalidates
 * nothing in the pool: it is additive, and the only input it needs is the QIDs the pool
 * already holds. Remap cannot show it (nobody fetched the property), but a full
 * re-extraction is the wrong price, so this fetches just those properties for just those
 * entities.
 */
public class EnrichInstancesQuery implements Query<GenerationRun> {

    private final GenerationRun previousRun;
    private final GeneratedProjectModel projectModel;
    private final wikidata.explore.generation.GenerationExecutionSettings executionSettings;

    public EnrichInstancesQuery(
            GenerationRun previousRun,
            GeneratedProjectModel projectModel) {

        this(previousRun, projectModel,
                new wikidata.explore.generation.GenerationExecutionSettings());
    }

    public EnrichInstancesQuery(
            GenerationRun previousRun, GeneratedProjectModel projectModel,
            wikidata.explore.generation.GenerationExecutionSettings executionSettings) {
        this.previousRun = previousRun;
        this.projectModel = projectModel;
        this.executionSettings = executionSettings;
    }

    @Override
    public String purpose() {
        return "Load newly declared fields (no re-extraction)";
    }

    @Override
    public String skeleton() {
        return "reuse downloaded objects -> materialize owned components -> "
                + "wbgetentities for declared PIDs -> stamp + canonicalize";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("rootClass", projectModel.rootClass().className());
        p.put("reusedObjects", String.valueOf(previousRun.dynamicObjects().size()));
        p.put("depth", String.valueOf(previousRun.depth()));
        p.put("cacheMb", String.valueOf(executionSettings.resolvedMemoryMb()));
        p.put("entityConcurrency", String.valueOf(executionSettings.concurrency()));
        p.put("requireComplete", String.valueOf(executionSettings.requireComplete()));
        return p;
    }

    @Override
    public GenerationRun execute(QueryContext context) throws Exception {
        // A STEP, not a bare message: the log window renders the tree, so a run that only
        // emits text sits at "Running..." saying nothing while its batches come and go.
        // Recording under a step gives every request its own entry — including one still
        // in flight, which is exactly the entry worth seeing when a run looks stuck.
        return context.step(
                "Enrich \"" + projectModel.name() + "\"",
                "Domain",   // a container node, not a SPARQL query
                null,
                parameters(),
                step -> {
                    wikidata.api.WikidataApiClient entityApi =
                            new wikidata.api.WikidataApiClient(
                                    wikidata.api.WikidataApiClient.DEFAULT_USER_AGENT)
                                    .facts(executionSettings.newFactStore())
                                    .entityConcurrency(executionSettings.concurrency())
                                    .cancellation(context.cancellation());

                    // Also teed to stdout: a fetch over thousands of entities is
                    // I/O-bound — near-zero CPU for minutes — so the process itself
                    // cannot answer "working, or blocked?".
                    return new GenerationPipeline().enrich(
                            previousRun, projectModel, entityApi,
                            StepGenerationLog.of(context, step, "enrich"));
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
