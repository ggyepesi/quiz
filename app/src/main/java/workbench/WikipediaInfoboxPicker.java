package workbench;

import wikidata.explore.query.logical.DiscoverWikipediaInfoboxQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;

import java.awt.Component;
import java.util.List;
import java.util.function.Consumer;

/** Shared Transform/ModelBuilder picker for native Wikipedia Infobox parameters. */
public final class WikipediaInfoboxPicker {
    private WikipediaInfoboxPicker() { }

    public static void findForEntities(Component parent, SwingQueryRunner runner,
            List<String> qids, Consumer<String> selected) {
        run(parent, runner, new DiscoverWikipediaInfoboxQuery(qids), selected);
    }
    public static void findByType(Component parent, SwingQueryRunner runner,
            String typeQid, int sampleSize, Consumer<String> selected) {
        run(parent, runner, new DiscoverWikipediaInfoboxQuery(typeQid, sampleSize), selected);
    }
    private static void run(Component parent, SwingQueryRunner runner,
            DiscoverWikipediaInfoboxQuery query, Consumer<String> selected) {
        SourceDiscoveryPicker.run(parent, runner, query,
                new SourceDiscoveryPicker.Spec<TableQueryResult>(
                        "Observed Wikipedia infobox parameters",
                        query.singleArticle()
                                ? "Parameters actually found in the selected article. Choose "
                                + "the one that supplies this field; <b>Examples</b> is what it "
                                + "held there."
                                // Categories rank least-shared first and parameters rank the
                                // other way. A reader who just used that picker will ask why:
                                // a category NAMES its value, so one everybody carries cannot
                                // be naming something that varies — while a parameter is a
                                // SLOT whose value varies, so the slot every article fills is
                                // the one worth reading.
                                : "Parameters actually found in the sampled articles, <b>most "
                                + "widely filled first</b>: a parameter is a slot, so one that "
                                + "every article fills is the one most likely to supply this "
                                + "field for every member. <b>Have</b> is how many carry it. "
                                + "Choose the parameter that supplies this field.",
                        "No native Wikipedia infobox parameters were found for this sample.",
                        "Use selected parameter",
                        result -> SourceDiscoveryPicker.infoboxRows(result == null
                                ? List.of() : result.rows())),
                choice -> { if (selected != null) selected.accept(choice.value()); },
                () -> { if (selected != null) selected.accept(null); });
    }
}
