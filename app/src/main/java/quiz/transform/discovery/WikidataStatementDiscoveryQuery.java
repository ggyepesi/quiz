package quiz.transform.discovery;

import wikidata.explore.query.core.Datasource;
import wikidata.explore.query.core.WikidataAccess;
import wikidata.statement.EntityStatementSummary;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The ModelBuilder statement inspection query, applied to an explicit instance sample. */
public final class WikidataStatementDiscoveryQuery
        implements Query<List<EntityStatementSummary>> {
    static final int BATCH_SIZE = 10;
    private final List<String> qids;

    public WikidataStatementDiscoveryQuery(List<String> qids) {
        this.qids = qids == null ? List.of() : qids.stream().distinct().toList();
    }

    @Override public String purpose() { return "Fetch every Wikidata statement and qualifier"; }
    @Override public String skeleton() {
        return "selected QIDs -> every property -> every statement -> every qualifier";
    }
    @Override public String description() {
        return "Fetch every Wikidata property, statement value and qualifier for the explicitly selected instances.";
    }
    @Override public Map<String, String> parameters() {
        return Map.of("instances", Integer.toString(qids.size()),
                "qids", String.join(",", qids));
    }

    @Override public List<EntityStatementSummary> execute(QueryContext context) throws Exception {
        var client = WikidataAccess.sparql(context, Datasource.WIKIDATA);
        return context.step("Fetch statements and qualifiers in QID batches",
                "Wikidata SPARQL", skeleton(), parameters(), step -> {
            List<batch.WorkUnit<List<wikidata.WikidataBinding>>> units = new ArrayList<>();
            for (int from = 0; from < qids.size(); from += BATCH_SIZE) {
                List<String> ids = qids.subList(from, Math.min(from + BATCH_SIZE, qids.size()));
                units.add(new wikidata.explore.extract.MemberBatchQueryUnit(
                        "wikidata.statement-discovery",
                        "Fetch every statement and qualifier", ids,
                        EntityStatementSummary::query, client, Map.of()));
            }
            Map<String, EntityStatementSummary> byQid = new LinkedHashMap<>();
            // The shared adapter, not a second rendering of one batch lifecycle. A local
            // BatchProgress implements only done/failed, leaving detail() and adapted()
            // at their defaults — so the executor's retry and split decisions, which is
            // exactly what a statements-and-qualifiers fetch produces, were dropped.
            batch.BatchProgress progress = wikidata.explore.query.logical.StepGenerationLog
                    .of(context, step).batchProgress();
            new batch.BatchExecutor<List<wikidata.WikidataBinding>>(
                    batch.BatchPolicy.defaults().withResume(false), progress,
                    wikidata.WikidataBatchFailureClassifier.INSTANCE,
                    context.cancellation(), batch.BatchCheckpointStore.NONE)
                    .run(units, (descriptor, rows) -> {
                        List<String> ids = List.of(descriptor.parameters()
                                .get(wikidata.explore.extract.MemberBatchQueryUnit.P_MEMBERS)
                                .split(","));
                        for (EntityStatementSummary summary
                                : EntityStatementSummary.fromRows(ids, rows)) {
                            byQid.put(summary.qid(), summary);
                        }
                    });
            List<EntityStatementSummary> ordered = qids.stream()
                    .map(byQid::get).filter(java.util.Objects::nonNull).toList();
            step.summary(summary(ordered));
            return ordered;
        });
    }

    @Override public int rowCount(List<EntityStatementSummary> result) {
        return result == null ? 0 : result.size();
    }

    @Override public String summary(List<EntityStatementSummary> result) {
        int properties = result == null ? 0 : result.stream()
                .mapToInt(value -> value.properties().size()).sum();
        return rowCount(result) + " instances, " + properties + " property sets";
    }
}
