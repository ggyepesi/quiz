package wikidata.explore.generation;

import datasource.api.SourceExecutionPlan;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

/**
 * Executes the field-acquisition portion of a resolved datasource plan.
 *
 * <p>Generate and Enrich share this orchestration. Each family retains its established
 * batching, retry, cache and checkpoint boundary; this class decides which prepared
 * families run and produces one common result instead of making every query reproduce
 * the dispatch sequence.
 */
public final class ExternalSourceAcquisition {
    private ExternalSourceAcquisition() { }

    public enum FailurePolicy { STRICT, CONTINUE_OPTIONAL }

    public static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, SourceExecutionPlan plan,
            WikidataSparqlClient dbpedia, wikidata.api.WikidataApiClient wikidata,
            GenerationLog log, work.CancellationToken cancellation,
            FailurePolicy failurePolicy) throws Exception {
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        work.CancellationToken token = cancellation == null
                ? new work.CancellationToken() : cancellation;

        DBpediaFieldAcquisition.Result dbpediaResult = new DBpediaFieldAcquisition.Result(0, 0);
        if (DBpediaFieldAcquisition.hasBindings(plan)) {
            if (dbpedia == null) {
                sink.message("DBpedia acquisition skipped: no process-bound client.\n");
            } else {
                try {
                    dbpediaResult = DBpediaFieldAcquisition.apply(
                            model, pool, plan, dbpedia, sink);
                } catch (Exception failure) {
                    if (failurePolicy != FailurePolicy.CONTINUE_OPTIONAL) throw failure;
                    sink.message("DBpedia enrichment failed: "
                            + failure.getMessage() + "\n");
                }
            }
        }

        WikipediaCategoryAcquisition.Result categories = wikidata == null
                ? new WikipediaCategoryAcquisition.Result(0, 0, 0)
                : WikipediaCategoryAcquisition.apply(
                        pool, sink, token, wikidata, plan);
        WikipediaInfoboxAcquisition.Result infoboxes = wikidata == null
                ? new WikipediaInfoboxAcquisition.Result(0, 0, 0)
                : WikipediaInfoboxAcquisition.apply(
                        model, pool, sink, token, wikidata, plan);
        return new Result(dbpediaResult, categories, infoboxes);
    }

    public record Result(
            DBpediaFieldAcquisition.Result dbpedia,
            WikipediaCategoryAcquisition.Result categories,
            WikipediaInfoboxAcquisition.Result infoboxes) {
        public int values() {
            return dbpedia.values() + categories.memberships() + infoboxes.values();
        }

        public String summary() {
            return categories.memberships() + " category membership(s), "
                    + infoboxes.values() + " infobox value(s), "
                    + dbpedia.values() + " DBpedia value(s)";
        }
    }
}
