package wikidata.explore.query.logical;

import wikidata.WikidataIds;

import wikidata.WikidataBinding;
import wikidata.explore.query.core.Datasource;
import work.Query;
import work.QueryContext;
import wikidata.explore.query.result.MembershipTypeCandidate;
import wikidata.explore.query.template.sparql.SparqlQueries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import wikidata.explore.query.core.WikidataAccess;

/**
 * Lists the direct subclasses of a class's membership type, each with the
 * number of entities whose direct P31 type is that subclass and one example.
 * The shared preview query separately shows the effective population with or
 * without P279 descendants before the user changes the class.
 */
public class DiscoverSubtypesQuery implements Query<List<MembershipTypeCandidate>> {

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
        return "direct subclasses (P279) + their direct P31 member count";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("baseQid", baseQid);
        return p;
    }

    @Override
    public List<MembershipTypeCandidate> execute(QueryContext context) throws Exception {
        String sparql = SparqlQueries.discoverMembershipSubtypes(baseQid, limit);

        return context.step(
                "Discover subtypes",
                "SPARQL",
                null,
                Map.of("baseQid", baseQid),
                step -> {
                    step.request(sparql);

                    List<MembershipTypeCandidate> rows = new ArrayList<>();
                    for (WikidataBinding b : WikidataAccess.sparql(context, Datasource.WIKIDATA).query(sparql)) {
                        String qid = b.qid("type");
                        if (qid == null || !WikidataIds.isQid(qid)) {
                            continue;
                        }
                        String label = b.value("typeLabel");
                        String nMembers = b.value("nMembers");
                        String examples = b.value("examples");
                        String exampleQid = b.qid("example");
                        List<MembershipTypeCandidate.Member> sample =
                                WikidataIds.isQid(exampleQid)
                                        ? List.of(new MembershipTypeCandidate.Member(
                                                exampleQid, examples)) : List.of();
                        rows.add(new MembershipTypeCandidate(qid, label,
                                MembershipTypeCandidate.Origin.DIRECT_SUBTYPE, baseQid,
                                0, 0, 0, number(nMembers), sample));
                    }

                    step.summary(rows.size() + " subtypes");
                    return List.copyOf(rows);
                });
    }

    private static long number(String value) {
        try { return value == null ? -1 : Long.parseLong(value); }
        catch (NumberFormatException ignored) { return -1; }
    }

    @Override
    public int rowCount(List<MembershipTypeCandidate> result) {
        return result == null ? 0 : result.size();
    }

    @Override
    public String summary(List<MembershipTypeCandidate> result) {
        return rowCount(result) + " subtypes";
    }
}
