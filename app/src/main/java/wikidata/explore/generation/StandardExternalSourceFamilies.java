package wikidata.explore.generation;

import java.util.List;

/** Standard per-run family catalogue; orchestration itself has no provider branches. */
public final class StandardExternalSourceFamilies {
    private StandardExternalSourceFamilies() { }

    public static ExternalSourceFamilyRegistry create() {
        return new ExternalSourceFamilyRegistry(List.of(
                new Dbpedia(), new Categories(), new Infobox()));
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
            if (context.dbpedia() == null) {
                context.log().message("DBpedia acquisition skipped: no process-bound client.\n");
                return empty();
            }
            DBpediaFieldAcquisition.Result result = DBpediaFieldAcquisition.apply(
                    context.model(), context.pool(), context.plan(), context.dbpedia(),
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
            if (context.wikidata() == null) {
                context.log().message("Wikipedia category acquisition skipped: no "
                        + "process-bound Wikidata client.\n");
                return empty();
            }
            WikipediaCategoryAcquisition.Result result = WikipediaCategoryAcquisition.apply(
                    context.pool(), context.log(), context.cancellation(),
                    context.wikidata(), context.plan());
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
            if (context.wikidata() == null) {
                context.log().message("Wikipedia infobox acquisition skipped: no "
                        + "process-bound Wikidata client.\n");
                return empty();
            }
            WikipediaInfoboxAcquisition.Result result = WikipediaInfoboxAcquisition.apply(
                    context.model(), context.pool(), context.log(), context.cancellation(),
                    context.wikidata(), context.plan());
            return new Outcome(id(), result.values(),
                    result.values() + " infobox value(s)", summaryOrder());
        }
    }
}
