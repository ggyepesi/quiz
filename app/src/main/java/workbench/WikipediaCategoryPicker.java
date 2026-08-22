package workbench;

import wikidata.explore.query.logical.DiscoverWikipediaCategoriesQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;

import java.awt.Component;
import java.util.List;
import java.util.function.Consumer;

/** Shared Transform/ModelBuilder picker for observed Wikipedia category titles. */
public final class WikipediaCategoryPicker {
    private WikipediaCategoryPicker() { }

    /** {@code selected} is handed the chosen category, or null when the reader made no
     * choice — so the caller's editor still opens on whatever is already configured. */
    public static void findForEntities(Component parent, SwingQueryRunner runner,
            List<String> qids, Consumer<String> selected) {
        run(parent, runner, new DiscoverWikipediaCategoriesQuery(qids), selected);
    }

    public static void findByType(Component parent, SwingQueryRunner runner,
            String typeQid, int sampleSize, Consumer<String> selected) {
        run(parent, runner,
                new DiscoverWikipediaCategoriesQuery(typeQid, sampleSize), selected);
    }

    private static void run(Component parent, SwingQueryRunner runner,
            DiscoverWikipediaCategoriesQuery query, Consumer<String> selected) {
        boolean singleArticle = query.singleArticle();
        SourceDiscoveryPicker.run(parent, runner, query,
                new SourceDiscoveryPicker.Spec<TableQueryResult>(
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
                        result -> SourceDiscoveryPicker.rows(
                                result == null ? List.of() : result.rows())),
                choice -> accept(selected, choice.value()),
                () -> accept(selected, null));
    }

    private static void accept(Consumer<String> selected, String category) {
        if (selected != null) selected.accept(category);
    }
}
