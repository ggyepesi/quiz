package wikidata.explore.workbench;

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
                        "Parameters actually found in the selected/sample articles. "
                                + "Choose the structural template parameter that supplies this field; "
                                + "Have is the number of sampled articles carrying it.",
                        "No native Wikipedia infobox parameters were found for this sample.",
                        "Use selected parameter",
                        result -> SourceDiscoveryPicker.rows(result == null
                                ? List.of() : result.rows())),
                choice -> { if (selected != null) selected.accept(choice.value()); },
                () -> { if (selected != null) selected.accept(null); });
    }
}
