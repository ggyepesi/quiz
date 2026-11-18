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

/** Counts to a visible ceiling and shows complete small or capped large memberships. */
public final class PreviewMembershipTypeQuery implements Query<MembershipTypePreview> {
    public static final int COUNT_BOUND = 1_000;
    public static final int MEMBER_LIMIT = 200;
    private final String qid;
    private final String label;
    private final boolean includeSubclasses;

    public PreviewMembershipTypeQuery(String qid, boolean includeSubclasses) {
        this(qid, "", includeSubclasses);
    }

    public PreviewMembershipTypeQuery(String qid, String label,
            boolean includeSubclasses) {
        if (!WikidataIds.isQid(qid)) throw new IllegalArgumentException("Choose a type QID.");
        this.qid = qid;
        this.label = label == null ? "" : label;
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
                "memberLimit", Integer.toString(MEMBER_LIMIT));
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
        int memberLimit = Math.min(MEMBER_LIMIT, Math.min(bounded, COUNT_BOUND));
        List<MembershipTypeCandidate.Member> members = memberLimit == 0
                ? List.of() : context.step(
                "Fetch " + (bounded <= MEMBER_LIMIT ? "all " : "up to ")
                        + memberLimit + " preview member(s) of " + qid,
                "SPARQL", skeleton(), parameters(), step -> {
                    String request = membersSparql(qid, relation, memberLimit);
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
                    step.summary(values.size() + " preview member(s)");
                    return List.copyOf(values);
                });
        return new MembershipTypePreview(qid, label, includeSubclasses,
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
        boolean complete = !result.countCapped()
                && result.members().size() == result.boundedCount();
        return (result.countCapped() ? result.boundedCount() + "+" : result.boundedCount())
                + " members; " + (complete ? "showing all " : "showing ")
                + result.members().size();
    }
}
