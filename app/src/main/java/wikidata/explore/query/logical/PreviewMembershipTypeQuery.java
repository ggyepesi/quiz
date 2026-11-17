package wikidata.explore.query.logical;

import wikidata.WikidataBinding;
import wikidata.WikidataIds;
import wikidata.explore.query.core.Datasource;
import wikidata.explore.query.core.WikidataAccess;
import wikidata.explore.query.result.MembershipTypeCandidate;
import wikidata.explore.query.result.MembershipTypePreview;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Counts up to a visible ceiling and samples members of one candidate type. */
public final class PreviewMembershipTypeQuery implements Query<MembershipTypePreview> {
    public static final int COUNT_BOUND = 1_000;
    public static final int SAMPLE_LIMIT = 50;
    private final String qid;
    private final boolean includeSubclasses;

    public PreviewMembershipTypeQuery(String qid, boolean includeSubclasses) {
        if (!WikidataIds.isQid(qid)) throw new IllegalArgumentException("Choose a type QID.");
        this.qid = qid;
        this.includeSubclasses = includeSubclasses;
    }

    @Override public String purpose() { return "Preview a membership type"; }
    @Override public String queryType() { return "Bounded SPARQL preview"; }
    @Override public String skeleton() {
        return includeSubclasses ? "member P31/P279* selected type"
                : "member P31 selected type";
    }
    @Override public Map<String, String> parameters() {
        return Map.of("typeQid", qid,
                "includeSubclasses", Boolean.toString(includeSubclasses),
                "countBound", Integer.toString(COUNT_BOUND),
                "sampleLimit", Integer.toString(SAMPLE_LIMIT));
    }

    @Override public MembershipTypePreview execute(QueryContext context) throws Exception {
        String relation = includeSubclasses ? "wdt:P31/wdt:P279*" : "wdt:P31";
        int bounded = context.step("Count members of " + qid + " up to " + COUNT_BOUND,
                "SPARQL", skeleton(), parameters(), step -> {
                    String request = countSparql(qid, relation, COUNT_BOUND + 1);
                    step.request(request);
                    List<WikidataBinding> rows = WikidataAccess
                            .sparql(context, Datasource.WIKIDATA).query(request);
                    int count = rows.isEmpty() ? 0 : number(rows.getFirst().value("count"));
                    step.summary(count > COUNT_BOUND ? COUNT_BOUND + "+ members"
                            : count + " members");
                    return count;
                });
        List<MembershipTypeCandidate.Member> members = context.step(
                "Fetch up to " + SAMPLE_LIMIT + " members of " + qid,
                "SPARQL", skeleton(), parameters(), step -> {
                    String request = membersSparql(qid, relation, SAMPLE_LIMIT);
                    step.request(request);
                    List<MembershipTypeCandidate.Member> values = new ArrayList<>();
                    for (WikidataBinding row : WikidataAccess
                            .sparql(context, Datasource.WIKIDATA).query(request)) {
                        String member = row.qid("member");
                        if (!WikidataIds.isQid(member)) continue;
                        values.add(new MembershipTypeCandidate.Member(member,
                                row.value("memberLabel"), row.value("memberDescription")));
                    }
                    values.sort(Comparator.comparing(
                            MembershipTypeCandidate.Member::label,
                            String.CASE_INSENSITIVE_ORDER));
                    step.summary(values.size() + " representative member(s)");
                    return List.copyOf(values);
                });
        return new MembershipTypePreview(qid, includeSubclasses,
                Math.min(bounded, COUNT_BOUND), bounded > COUNT_BOUND, members);
    }

    static String countSparql(String qid, String relation, int limit) {
        return "SELECT (COUNT(?member) AS ?count) WHERE {\n"
                + "  { SELECT DISTINCT ?member WHERE { ?member " + relation + " wd:"
                + qid + " . } LIMIT " + limit + " }\n}";
    }

    static String membersSparql(String qid, String relation, int limit) {
        return """
                SELECT DISTINCT ?member ?memberLabel ?memberDescription WHERE {
                  {
                    SELECT DISTINCT ?member WHERE {
                      ?member %s wd:%s .
                    }
                    LIMIT %d
                  }
                %s}
                """.formatted(relation, qid, limit,
                wikidata.query.LabelService.serviceBinding(null,
                        "    ?member rdfs:label ?memberLabel.\n"
                                + "    ?member schema:description ?memberDescription.\n"));
    }

    private static int number(String value) {
        try { return value == null ? 0 : Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    @Override public int rowCount(MembershipTypePreview result) {
        return result == null ? 0 : result.members().size();
    }
    @Override public String summary(MembershipTypePreview result) {
        if (result == null) return "0 members";
        return (result.countCapped() ? result.boundedCount() + "+" : result.boundedCount())
                + " members; " + result.members().size() + " shown";
    }
}
