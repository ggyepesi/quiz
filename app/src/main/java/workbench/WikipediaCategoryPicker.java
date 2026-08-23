package workbench;

import datasource.EntityRef;
import datasource.Datasources;
import datasource.api.DatasourceRegistry;
import datasource.api.discovery.SourceDiscoveryOperation;
import datasource.api.discovery.SourceDiscoveryRequest;
import datasource.api.discovery.SourceDiscoveryResult;
import datasource.wikipedia.WikipediaCategoryDiscoveryOperation;
import datasource.wikipedia.WikipediaDatasourceProvider;
import wikidata.explore.query.swing.SwingQueryRunner;

import java.awt.Component;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Shared Transform/ModelBuilder picker for observed Wikipedia category titles. */
public final class WikipediaCategoryPicker {
    private WikipediaCategoryPicker() { }

    /** {@code selected} is handed the chosen category, or null when the reader made no
     * choice — so the caller's editor still opens on whatever is already configured. */
    public static void findForEntities(Component parent, SwingQueryRunner runner,
            List<String> qids, Consumer<String> selected) {
        findForEntities(parent, runner, Datasources.standard(), qids, selected);
    }

    /** Told which datasources are available rather than reaching for a shared set, so a
     *  caller can be given a different registry and a test needs no global state. */
    public static void findForEntities(Component parent, SwingQueryRunner runner,
            DatasourceRegistry datasources, List<String> qids, Consumer<String> selected) {
        List<EntityRef> seeds = qids == null ? List.of() : qids.stream()
                .filter(wikidata.WikidataIds::isQid)
                .distinct().limit(12).map(EntityRef::wikidata).toList();
        run(parent, runner, datasources, new SourceDiscoveryRequest(seeds, Map.of()),
                selected);
    }

    public static void findByType(Component parent, SwingQueryRunner runner,
            String typeQid, int sampleSize, Consumer<String> selected) {
        findByType(parent, runner, Datasources.standard(), typeQid, sampleSize, selected);
    }

    public static void findByType(Component parent, SwingQueryRunner runner,
            DatasourceRegistry datasources, String typeQid, int sampleSize,
            Consumer<String> selected) {
        run(parent, runner, datasources, new SourceDiscoveryRequest(List.of(), Map.of(
                WikipediaCategoryDiscoveryOperation.TYPE_QID,
                typeQid == null ? "" : typeQid,
                WikipediaCategoryDiscoveryOperation.SAMPLE_SIZE,
                Integer.toString(sampleSize))), selected);
    }

    private static void run(Component parent, SwingQueryRunner runner,
            DatasourceRegistry datasources, SourceDiscoveryRequest request,
            Consumer<String> selected) {
        // require, not orElse(null): a missing operation means the application was
        // assembled without Wikipedia, which is a fault to report rather than a reason
        // to open a picker that silently finds nothing.
        SourceDiscoveryOperation operation = datasources.require(
                WikipediaDatasourceProvider.ID, WikipediaCategoryDiscoveryOperation.ID,
                SourceDiscoveryOperation.class);
        work.Query<SourceDiscoveryResult> query = operation.discover(request);
        boolean singleArticle = request.seeds().size() == 1;
        SourceDiscoveryPicker.run(parent, runner, query,
                new SourceDiscoveryPicker.Spec<SourceDiscoveryResult>(
                        "Observed Wikipedia categories",
                        singleArticle
                                ? "Categories actually found on the selected article. Choose "
                                + "one, then replace the part that names the desired field "
                                + "value with <b>&lt;value&gt;</b>."
                                : "Categories actually found on the selected/sample articles, "
                                + "<b>least shared first</b>: one that every article carries "
                                + "describes the sample, not the member, so it cannot name a "
                                + "value that varies. <b>Have</b> is how many carry it. Choose "
                                + "one, then replace the part that names the desired field "
                                + "value with <b>&lt;value&gt;</b>.",
                        "No English Wikipedia categories were found for the selected sample.",
                        "Use selected category",
                        SourceDiscoveryPicker::rows),
                choice -> accept(selected, choice.value()),
                () -> accept(selected, null));
    }

    private static void accept(Consumer<String> selected, String category) {
        if (selected != null) selected.accept(category);
    }
}
