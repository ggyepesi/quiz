package wikidata.explore.query.logical;

import wikidata.WikidataIds;

import wikidata.WikidataBinding;
import wikidata.explore.query.core.Datasource;
import work.Query;
import work.QueryContext;
import wikidata.explore.query.result.TableQueryResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import wikidata.explore.query.core.WikidataAccess;

/**
 * Fetches the members of ONE relation of an entity, on demand — e.g. every entity
 * with {@code ?m wdt:P1411 wd:Q102427} ("nominated for Best Picture"). The
 * predicate is BOUND here (unlike the general {@link ExploreEntityQuery} scan), so
 * even a 1000-member relation returns in ~1s. Feeds a class's Seed QIDs after the
 * user picks a relation in the explorer.
 */
public class RelationMembersQuery implements Query<TableQueryResult> {

    private final String qid;
    private final String pid;
    private final boolean incoming;
    private final int limit;

    public RelationMembersQuery(String qid, String pid, boolean incoming, int limit) {
        this.qid = qid == null ? "" : qid.trim();
        this.pid = pid == null ? "" : pid.trim();
        this.incoming = incoming;
        this.limit = Math.max(1, limit);
    }

    @Override
    public String purpose() {
        return "Fetch relation members";
    }

    @Override
    public String skeleton() {
        return "members of one (bound) relation -> seed QIDs";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("qid", qid, "pid", pid,
                "direction", incoming ? "in" : "out", "limit", String.valueOf(limit));
    }

    private String sparql() {
        String e = "wd:" + qid;
        String triple = incoming
                ? "?m wdt:" + pid + " " + e + " ."
                : e + " wdt:" + pid + " ?m . FILTER(isIRI(?m))";
        return "SELECT DISTINCT ?m ?mLabel WHERE {\n"
                + "  " + triple + "\n"
                + "  OPTIONAL { ?m rdfs:label ?mLabel . FILTER(LANG(?mLabel) = \"en\") }\n"
                + "} LIMIT " + limit;
    }

    @Override
    public TableQueryResult execute(QueryContext context) throws Exception {
        String sparql = sparql();
        return context.step(
                "Members of " + pid + (incoming ? " ←" : " →") + " " + qid,
                "SPARQL",
                null,
                Map.of("qid", qid, "pid", pid),
                step -> {
                    step.request(sparql);
                    List<List<Object>> rows = new ArrayList<>();
                    for (WikidataBinding b : WikidataAccess.sparql(context, Datasource.WIKIDATA).query(sparql)) {
                        String m = b.qid("m");
                        if (m == null || !WikidataIds.isQid(m)) {
                            continue;
                        }
                        String label = b.value("mLabel");
                        rows.add(List.of(m, label == null || label.isBlank() ? m : label));
                    }
                    step.summary(rows.size() + " members");
                    return new TableQueryResult(List.of("QID", "Label"), rows);
                });
    }

    @Override
    public int rowCount(TableQueryResult result) {
        return result == null ? 0 : result.size();
    }

    @Override
    public String summary(TableQueryResult result) {
        return rowCount(result) + " members";
    }
}
