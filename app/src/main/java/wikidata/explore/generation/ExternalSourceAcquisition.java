package wikidata.explore.generation;

import datasource.api.SourceExecutionPlan;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

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
    public enum Family { DBPEDIA, WIKIPEDIA_CATEGORIES, WIKIPEDIA_INFOBOX }

    public static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, SourceExecutionPlan plan,
            WikidataSparqlClient dbpedia, wikidata.api.WikidataApiClient wikidata,
            GenerationLog log, work.CancellationToken cancellation,
            FailurePolicy failurePolicy) throws Exception {
        return apply(model, pool, plan, dbpedia, wikidata, log, cancellation,
                failurePolicy, Set.of(Family.values()));
    }

    public static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, SourceExecutionPlan plan,
            WikidataSparqlClient dbpedia, wikidata.api.WikidataApiClient wikidata,
            GenerationLog log, work.CancellationToken cancellation,
            FailurePolicy failurePolicy, Set<Family> selectedFamilies) throws Exception {
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        work.CancellationToken token = cancellation == null
                ? new work.CancellationToken() : cancellation;
        Set<Family> selected = selectedFamilies == null ? Set.of() : Set.copyOf(selectedFamilies);

        DBpediaFieldAcquisition.Result dbpediaResult = new DBpediaFieldAcquisition.Result(0, 0);
        if (selected.contains(Family.DBPEDIA) && acquires(plan,
                datasource.dbpedia.DbpediaDatasourceProvider.FAMILY_FIELD)) {
            if (dbpedia == null) {
                sink.message("DBpedia acquisition skipped: no process-bound client.\n");
            } else {
                dbpediaResult = run("DBpedia", failurePolicy, sink,
                        new DBpediaFieldAcquisition.Result(0, 0), () ->
                                DBpediaFieldAcquisition.apply(
                                        model, pool, plan, dbpedia, sink));
            }
        }

        WikipediaCategoryAcquisition.Result categories =
                new WikipediaCategoryAcquisition.Result(0, 0, 0);
        if (selected.contains(Family.WIKIPEDIA_CATEGORIES)
                && acquires(plan, datasource.wikipedia
                        .WikipediaCategoryDiscoveryOperation.FAMILY)) {
            if (wikidata == null) {
                sink.message("Wikipedia category acquisition skipped: no process-bound "
                        + "Wikidata client.\n");
            } else {
                categories = run("Wikipedia category", failurePolicy, sink, categories, () ->
                        WikipediaCategoryAcquisition.apply(
                                pool, sink, token, wikidata, plan));
            }
        }

        WikipediaInfoboxAcquisition.Result infoboxes =
                new WikipediaInfoboxAcquisition.Result(0, 0, 0);
        if (selected.contains(Family.WIKIPEDIA_INFOBOX)
                && acquires(plan, datasource.wikipedia.WikipediaDatasourceProvider
                        .FAMILY_INFOBOX_FIELD)) {
            if (wikidata == null) {
                sink.message("Wikipedia infobox acquisition skipped: no process-bound "
                        + "Wikidata client.\n");
            } else {
                infoboxes = run("Wikipedia infobox", failurePolicy, sink, infoboxes, () ->
                        WikipediaInfoboxAcquisition.apply(
                                model, pool, sink, token, wikidata, plan));
            }
        }
        return new Result(dbpediaResult, categories, infoboxes);
    }

    private static boolean acquires(SourceExecutionPlan plan, String familyId) {
        return plan != null && plan.acquires(familyId);
    }

    static <T> T run(String family, FailurePolicy policy, GenerationLog log,
            T fallback, Callable<T> work) throws Exception {
        try {
            return work.call();
        } catch (CancellationException | InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            if (policy != FailurePolicy.CONTINUE_OPTIONAL) throw failure;
            log.message(family + " acquisition failed; continuing: "
                    + failure.getMessage() + "\n");
            return fallback;
        }
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
