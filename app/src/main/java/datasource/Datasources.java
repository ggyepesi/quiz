package datasource;

import datasource.api.DatasourceRegistry;
import datasource.wikidata.WikidataDatasourceProvider;
import datasource.wikipedia.WikipediaDatasourceProvider;
import datasource.dbpedia.DbpediaDatasourceProvider;

import java.util.List;

/**
 * Application composition root for the datasource plugins shipped with Quiz.
 *
 * <p>{@link #standard()} builds a registry; it does not hold one. A shared instance
 * would be a service locator, and a caller reaching into it could not be given a
 * different set of providers — which is the whole point of a registry, and the reason
 * a domain's storage root is a constructor parameter rather than a static.
 */
public final class Datasources {

    private Datasources() { }

    /** The providers this application ships. Callers take a registry as a parameter;
     *  the composition root is the one place that decides what is in it. */
    public static DatasourceRegistry standard() {
        return new DatasourceRegistry(List.of(
                new DbpediaDatasourceProvider(),
                new WikipediaDatasourceProvider(),
                new WikidataDatasourceProvider()));
    }
}
