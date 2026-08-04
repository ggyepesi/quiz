package wikidata.explore.query.logical;

import wikidata.WikidataIds;

import wikidata.WikidataBinding;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.template.sparql.SparqlQueries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists the direct subclasses of a class's membership type, each with the
 * number of NEW instances it would add (entities not already members) and a
 * few examples — so the user can pick which subtype QIDs to add to a class's
 * multi-QID membership ("Also include types") and see the impact first.
 */
public class DiscoverSubtypesQuery implements Query<TableQueryResult> {

    private final String baseQid;
    private final int limit;

    public DiscoverSubtypesQuery(String baseQid, int limit) {
        this.baseQid = baseQid == null ? "" : baseQid.trim();
        this.limit = Math.max(1, limit);
    }

    @Override
    public String purpose() {
        return "Discover membership subtypes";
    }

    @Override
    public String skeleton() {
        return "subclasses (P279) of the membership type + count of new instances";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("baseQid", baseQid);
        return p;
    }

    @Override
    public TableQueryResult execute(QueryContext context) throws Exception {
        String sparql = SparqlQueries.discoverMembershipSubtypes(baseQid, limit);

        return context.step(
                "Discover subtypes",
                "SPARQL",
                null,
                Map.of("baseQid", baseQid),
                step -> {
                    step.request(sparql);

                    List<List<Object>> rows = new ArrayList<>();
                    for (WikidataBinding b : context.sparql().query(sparql)) {
                        String qid = b.qid("type");
                        if (qid == null || !WikidataIds.isQid(qid)) {
                            continue;
                        }
                        String label = b.value("typeLabel");
                        String nNew = b.value("nNew");
                        String examples = b.value("examples");
                        rows.add(List.of(
                                label == null || label.isBlank() ? qid : label,
                                nNew == null ? "" : nNew,
                                examples == null ? "" : examples,
                                qid));
                    }

                    step.summary(rows.size() + " subtypes");
                    return new TableQueryResult(
                            List.of("Subtype", "Adds", "Examples", "QID"),
                            rows);
                });
    }

    @Override
    public int rowCount(TableQueryResult result) {
        return result == null ? 0 : result.size();
    }

    @Override
    public String summary(TableQueryResult result) {
        return rowCount(result) + " subtypes";
    }
}
