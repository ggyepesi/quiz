package wikidata.explore.generation;

import java.util.List;

/** Standard per-run family catalogue; orchestration itself has no provider branches. */
public final class StandardExternalSourceFamilies {
    private StandardExternalSourceFamilies() { }

    /** Adapts the application's standard process clients to the provider-neutral seam. */
    public static datasource.api.SourceRuntimeServices services(
            wikidata.WikidataSparqlClient dbpedia,
            wikidata.api.WikidataApiClient wikidata) {
        return services(null, dbpedia, wikidata);
    }

    public static datasource.api.SourceRuntimeServices services(
            wikidata.WikidataSparqlClient wikidataSparql,
            wikidata.WikidataSparqlClient dbpedia,
            wikidata.api.WikidataApiClient wikidata) {
        return datasource.api.SourceRuntimeServices.builder()
                .put(datasource.wikidata.WikidataDatasourceProvider.ID,
                        wikidata.WikidataSparqlClient.class, wikidataSparql)
                .put(datasource.dbpedia.DbpediaDatasourceProvider.ID,
                        wikidata.WikidataSparqlClient.class, dbpedia)
                .put(datasource.wikidata.WikidataDatasourceProvider.ID,
                        wikidata.api.WikidataApiClient.class, wikidata)
                .build();
    }

    /** The configured families, by name, for a phase that says what it is about to read. */
    public static String describe(datasource.api.SourceExecutionPlan plan) {
        List<String> names = create().families().stream()
                .filter(family -> family.configured(plan))
                .map(ExternalSourceFamily::displayName).toList();
        return names.isEmpty() ? "no external field sources are configured"
                : String.join(", ", names);
    }

    public static ExternalSourceFamilyRegistry create() {
        return new ExternalSourceFamilyRegistry(List.of(
                new WikidataComputed(), new Dbpedia(), new Categories(),
                new ArticleIntroductions(), new Infobox(), new Pageviews()));
    }

    private record WikidataComputed() implements ExternalSourceFamily {
        @Override public String id() {
            return datasource.wikidata.WikidataDatasourceProvider.FAMILY_COMPUTED_FIELD;
        }
        @Override public String displayName() { return "Wikidata computed fields"; }
        @Override public int summaryOrder() { return 5; }
        @Override public boolean configured(datasource.api.SourceExecutionPlan plan) {
            return plan != null && plan.acquires(id());
        }
        @Override public Outcome empty() {
            return new Outcome(id(), 0, "0 Wikidata computed value(s)", summaryOrder());
        }
        @Override public Outcome acquire(Context context) throws Exception {
            wikidata.WikidataSparqlClient client = context.services().find(
                    datasource.wikidata.WikidataDatasourceProvider.ID,
                    wikidata.WikidataSparqlClient.class).orElse(null);
            if (client == null) {
                context.log().message("Wikidata computed-field acquisition skipped: no "
                        + "process-bound Wikidata SPARQL client.\n");
                return empty();
            }
            WikidataComputedFieldAcquisition.Result result =
                    WikidataComputedFieldAcquisition.apply(context.model(), context.pool(),
                            context.plan(), client, context.log(), context.cancellation());
            return new Outcome(id(), result.values(),
                    result.values() + " Wikidata computed value(s)", summaryOrder());
        }
    }

    private record Dbpedia() implements ExternalSourceFamily {
        @Override public String id() {
            return datasource.dbpedia.DbpediaDatasourceProvider.FAMILY_FIELD;
        }
        @Override public String displayName() { return "DBpedia"; }
        @Override public int summaryOrder() { return 30; }
        @Override public boolean configured(datasource.api.SourceExecutionPlan plan) {
            return plan != null && plan.acquires(id());
        }
        @Override public Outcome empty() {
            return new Outcome(id(), 0, "0 DBpedia value(s)", summaryOrder());
        }
        @Override public Outcome acquire(Context context) throws Exception {
            wikidata.WikidataSparqlClient client = context.services().find(
                    datasource.dbpedia.DbpediaDatasourceProvider.ID,
                    wikidata.WikidataSparqlClient.class).orElse(null);
            if (client == null) {
                context.log().message("DBpedia acquisition skipped: no process-bound client.\n");
                return empty();
            }
            DBpediaFieldAcquisition.Result result = DBpediaFieldAcquisition.apply(
                    context.model(), context.pool(), context.plan(), client,
                    context.log());
            return new Outcome(id(), result.values(),
                    result.values() + " DBpedia value(s)", summaryOrder());
        }
    }

    private record Categories() implements ExternalSourceFamily {
        @Override public String id() {
            return datasource.wikipedia.WikipediaCategoryDiscoveryOperation.FAMILY;
        }
        @Override public String displayName() { return "Wikipedia category"; }
        @Override public int summaryOrder() { return 10; }
        @Override public boolean configured(datasource.api.SourceExecutionPlan plan) {
            return plan != null && plan.acquires(id());
        }
        @Override public Outcome empty() {
            return new Outcome(id(), 0, "0 category membership(s)", summaryOrder());
        }
        @Override public Outcome acquire(Context context) throws Exception {
            wikidata.api.WikidataApiClient client = context.services().find(
                    datasource.wikidata.WikidataDatasourceProvider.ID,
                    wikidata.api.WikidataApiClient.class).orElse(null);
            if (client == null) {
                context.log().message("Wikipedia category acquisition skipped: no "
                        + "process-bound Wikidata client.\n");
                return empty();
            }
            WikipediaCategoryAcquisition.Result result = WikipediaCategoryAcquisition.apply(
                    context.pool(), context.log(), context.cancellation(),
                    client, context.plan());
            return new Outcome(id(), result.memberships(),
                    result.memberships() + " category membership(s)", summaryOrder());
        }
    }

    private record Infobox() implements ExternalSourceFamily {
        @Override public String id() {
            return datasource.wikipedia.WikipediaDatasourceProvider.FAMILY_INFOBOX_FIELD;
        }
        @Override public String displayName() { return "Wikipedia infobox"; }
        @Override public int summaryOrder() { return 20; }
        @Override public boolean configured(datasource.api.SourceExecutionPlan plan) {
            return plan != null && plan.acquires(id());
        }
        @Override public Outcome empty() {
            return new Outcome(id(), 0, "0 infobox value(s)", summaryOrder());
        }
        @Override public Outcome acquire(Context context) throws Exception {
            wikidata.api.WikidataApiClient client = context.services().find(
                    datasource.wikidata.WikidataDatasourceProvider.ID,
                    wikidata.api.WikidataApiClient.class).orElse(null);
            if (client == null) {
                context.log().message("Wikipedia infobox acquisition skipped: no "
                        + "process-bound Wikidata client.\n");
                return empty();
            }
            WikipediaInfoboxAcquisition.Result result = WikipediaInfoboxAcquisition.apply(
                    context.model(), context.pool(), context.log(), context.cancellation(),
                    client, context.plan());
            return new Outcome(id(), result.values(),
                    result.values() + " infobox value(s)", summaryOrder());
        }
    }

    private record ArticleIntroductions() implements ExternalSourceFamily {
        @Override public String id() {
            return datasource.wikipedia.WikipediaDatasourceProvider
                    .FAMILY_ARTICLE_INTRODUCTION_FIELD;
        }
        @Override public String displayName() { return "Wikipedia article text"; }
        @Override public int summaryOrder() { return 15; }
        @Override public boolean configured(datasource.api.SourceExecutionPlan plan) {
            return plan != null && plan.acquires(id());
        }
        @Override public Outcome empty() {
            return new Outcome(id(), 0, "0 article text value(s)", summaryOrder());
        }
        @Override public Outcome acquire(Context context) throws Exception {
            wikidata.api.WikidataApiClient client = context.services().find(
                    datasource.wikidata.WikidataDatasourceProvider.ID,
                    wikidata.api.WikidataApiClient.class).orElse(null);
            WikipediaArticleIntroductionAcquisition.Result result =
                    WikipediaArticleIntroductionAcquisition.apply(
                            context.model(), context.pool(), context.log(),
                            context.cancellation(), client, context.plan());
            return new Outcome(id(), result.values(),
                    result.values() + " article text value(s)", summaryOrder());
        }
    }

    private record Pageviews() implements ExternalSourceFamily {
        @Override public String id() {
            return datasource.pageviews.PageviewsDatasourceProvider.FAMILY_FIELD;
        }
        @Override public String displayName() { return "Wikimedia Pageviews"; }
        @Override public int summaryOrder() { return 40; }
        @Override public boolean configured(datasource.api.SourceExecutionPlan plan) {
            return plan != null && plan.acquires(id());
        }
        @Override public Outcome empty() {
            return new Outcome(id(), 0, "0 pageview value(s)", summaryOrder());
        }
        @Override public Outcome acquire(Context context) throws Exception {
            // The article comes from the entity's Wikipedia correspondence, which the
            // Wikidata entity client resolves when it was not retained.
            wikidata.api.WikidataApiClient client = context.services().find(
                    datasource.wikidata.WikidataDatasourceProvider.ID,
                    wikidata.api.WikidataApiClient.class).orElse(null);
            PageviewsFieldAcquisition.Result result = PageviewsFieldAcquisition.apply(
                    context.model(), context.pool(), context.log(),
                    context.cancellation(), client, context.plan());
            return new Outcome(id(), result.values(),
                    result.values() + " pageview value(s)", summaryOrder());
        }
    }
}
