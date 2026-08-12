package quiz.enrichment;

import wikidata.api.WikidataEntityLabelResolver;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** One interactive, sequential label-repair operation over a set of QIDs. */
public final class ResolveReferenceLabelsQuery
        implements Query<WikidataEntityLabelResolver.Result> {

    private final List<String> qids;

    public ResolveReferenceLabelsQuery(Collection<String> qids) {
        this.qids = qids == null ? List.of() : List.copyOf(qids);
    }

    @Override public String purpose() { return "Resolve reference names"; }
    @Override public String skeleton() { return "wbgetentities labels, sequential batches of 50"; }
    @Override public String queryType() { return "Wikidata API"; }
    @Override public String description() { return "Repair QID-only reference names"; }
    @Override public Map<String, String> parameters() {
        return Map.of("references", Integer.toString(qids.size()));
    }

    @Override public WikidataEntityLabelResolver.Result execute(QueryContext context)
            throws Exception {
        if (context.api() == null) {
            throw new IllegalStateException("No Wikidata API client configured");
        }
        return context.step(purpose(), queryType(), skeleton(), parameters(), step -> {
            WikidataEntityLabelResolver.Result result =
                    new WikidataEntityLabelResolver(context.api()).resolve(
                            qids, WikidataEntityLabelResolver.Execution.SEQUENTIAL, null);
            step.summary(result.labels().size() + " label(s), "
                    + result.failedBatches() + " failed batch(es)");
            return result;
        });
    }

    @Override public int rowCount(WikidataEntityLabelResolver.Result result) {
        return result == null ? 0 : result.labels().size();
    }
}
