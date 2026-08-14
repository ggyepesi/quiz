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

    public EnrichInstancesQuery(
            GenerationRun previousRun,
            GeneratedProjectModel projectModel) {

        this.previousRun = previousRun;
        this.projectModel = projectModel;
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
        return p;
    }

    @Override
    public GenerationRun execute(QueryContext context) throws Exception {
        context.message("Enriching " + previousRun.dynamicObjects().size()
                + " downloaded objects: fetching only the declared properties still "
                + "missing.");

        wikidata.api.WikidataApiClient entityApi =
                new wikidata.api.WikidataApiClient(
                        wikidata.api.WikidataApiClient.DEFAULT_USER_AGENT)
                        .cancellation(context.cancellation());

        // Tee to stdout as well as the log window. A fetch over thousands of entities is
        // I/O-bound — near-zero CPU for minutes — so "is it working or blocked?" cannot
        // be answered by watching the process. Every batch prints, timestamped, where a
        // hung request shows as a line that stops advancing.
        return new GenerationPipeline().enrich(
                previousRun, projectModel, entityApi,
                wikidata.explore.extract.GenerationLog.of(text -> {
                    context.message(text);
                    echo(text);
                }));
    }

    private static void echo(String text) {
        if (text == null || text.isBlank()) return;
        String stamp = java.time.LocalTime.now().withNano(0).toString();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) System.out.println("[enrich " + stamp + "] " + line);
        }
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
