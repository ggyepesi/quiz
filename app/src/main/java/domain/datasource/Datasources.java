package domain.datasource;

import domain.datasource.api.DatasourceRegistry;
import domain.datasource.wikidata.WikidataDatasourceProvider;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;
import domain.datasource.dbpedia.DbpediaDatasourceProvider;
import domain.datasource.pageviews.PageviewsDatasourceProvider;

import java.util.ArrayList;
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
        List<domain.datasource.api.DatasourceProvider> providers = new ArrayList<>(shipped());
        providers.addAll(configured().providers());
        return new DatasourceRegistry(providers);
    }

    /**
     * The configured providers installed beside the shipped ones, read once per process.
     * A configured id that repeats a shipped or an earlier configured id is not installed,
     * so composing {@link #standard()} cannot fail on a duplicate.
     */
    public static domain.datasource.configured.ConfiguredDatasourceCatalog.Installed configured() {
        return Configured.INSTALLED;
    }

    private static final class Configured {
        static final domain.datasource.configured.ConfiguredDatasourceCatalog.Installed INSTALLED =
                domain.datasource.configured.ConfiguredDatasourceCatalog.read(
                        domain.datasource.configured.ConfiguredDatasourceCatalog.standardDirectory(),
                        shipped().stream().map(domain.datasource.api.DatasourceProvider::id)
                                .collect(java.util.stream.Collectors.toSet()));
    }

    private static List<domain.datasource.api.DatasourceProvider> shipped() {
        return List.of(
                new DbpediaDatasourceProvider(),
                new WikipediaDatasourceProvider(),
                new WikidataDatasourceProvider(),
                new PageviewsDatasourceProvider());
    }
}
