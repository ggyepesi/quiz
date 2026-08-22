package workbench;

import wikidata.explore.query.logical.DiscoverDBpediaPropertiesQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;

import java.awt.Component;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A passive picker for a DBpedia (Wikipedia infobox) property, parallel to Explore's
 * {@code findProperty}: given the seed instances to inspect, it lists their {@code dbp:}
 * infobox properties (Property / Have / Example) and RETURNS the chosen one to the caller
 * via {@code onSelected} — it never sets anything itself. Curate uses it to pick a field's
 * DBpedia source; the same picker can back the ModelBuilder source panel's discovery.
 */
public final class DbpediaPropertyPicker {

    private DbpediaPropertyPicker() { }

    /** Inspect {@code seedQids}' DBpedia infobox properties and deliver the picked
     *  {@code (property, exampleValue)} to {@code onSelected}. */
    public static void findProperty(
            Component parent, SwingQueryRunner runner,
            List<String> seedQids, BiConsumer<String, String> onSelected) {
        if (seedQids == null || seedQids.isEmpty()) {
            return;
        }
        run(parent, runner, new DiscoverDBpediaPropertiesQuery(seedQids), onSelected);
    }

    /** Sample {@code sampleSize} instances of the class {@code typeQid}, then pick from their
     *  DBpedia infobox properties — for a caller (e.g. the source panel) that has the class
     *  type rather than specific instances. */
    public static void findPropertyByType(
            Component parent, SwingQueryRunner runner,
            String typeQid, int sampleSize, BiConsumer<String, String> onSelected) {
        run(parent, runner, new DiscoverDBpediaPropertiesQuery(typeQid, sampleSize), onSelected);
    }

    private static void run(
            Component parent, SwingQueryRunner runner,
            DiscoverDBpediaPropertiesQuery query, BiConsumer<String, String> onSelected) {
        SourceDiscoveryPicker.run(parent, runner, query,
                new SourceDiscoveryPicker.Spec<TableQueryResult>(
                        "DBpedia infobox properties",
                        "Wikipedia-infobox (DBpedia) properties of the sampled instances, "
                                + "reached via their Wikidata <i>sameAs</i>. <b>Have</b> is "
                                + "coverage; <b>Examples</b> is a sample value.",
                        "No Wikipedia-infobox properties were found for the sample.",
                        "Use selected property",
                        result -> SourceDiscoveryPicker.rows(
                                result == null ? List.of() : result.rows())),
                choice -> {
                    if (onSelected != null) {
                        onSelected.accept(choice.value(), choice.examples());
                    }
                },
                // Nothing picked means nothing to set: this picker only ever reports a
                // choice, so a dismissal leaves the caller's current source alone.
                () -> { });
    }
}
