package workbench;

import wikidata.explore.WikidataProperty;
import wikidata.explore.WikidataPropertyStore;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.ui.WikidataLabels;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gives {@link WikidataLabels} what it needs in a workbench (#404): the property labels
 * already held locally, and the batched entity-label lookup run through the
 * application's own query runner, so each fetch appears in its query log. One
 * installation per application, shared by ModelBuilder and TransformApp.
 */
public final class WikidataLabelLookup {

    private WikidataLabelLookup() {
    }

    public static void install(SwingQueryRunner runner) {
        quiz.enrichment.WikimediaEntityLookup lookup =
                new quiz.enrichment.WikimediaEntityLookup();
        WikidataLabels.resolveWith((ids, found) ->
                runner.runQuiet(lookup.labels(ids), found::accept, ignored -> { }));
        Thread seed = new Thread(WikidataLabelLookup::seedProperties,
                "Read property labels");
        seed.setDaemon(true);
        seed.start();
    }

    private static void seedProperties() {
        try {
            Map<String, String> labels = new LinkedHashMap<>();
            for (WikidataProperty property : new WikidataPropertyStore().read()) {
                labels.put(property.pid(), property.label());
            }
            WikidataLabels.seed(labels);
        } catch (Exception ignored) {
            // No local property list: PIDs are then looked up like any other id.
        }
    }
}
