package wikidata.explore.query.logical;

import wikidata.WikidataBinding;
import wikidata.WikidataIds;
import wikidata.query.core.Datasource;
import wikidata.query.core.WikidataAccess;
import wikidata.explore.query.result.MembershipTypeCandidate;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds direct and inherited Wikidata types shared by explicit example entities. */
public final class DiscoverCommonMembershipTypesQuery
        implements Query<List<MembershipTypeCandidate>> {

    public static final int MAX_EXAMPLES = 20;
    private static final int MAX_CANDIDATES = 200;
    private final List<MembershipTypeCandidate.Member> examples;

    public DiscoverCommonMembershipTypesQuery(
            List<MembershipTypeCandidate.Member> examples) {
        Map<String, MembershipTypeCandidate.Member> byQid = new LinkedHashMap<>();
        if (examples != null) {
            examples.stream()
                    .filter(example -> example != null
                            && WikidataIds.isQid(example.qid()))
                    .forEach(example -> byQid.putIfAbsent(example.qid(), example));
        }
        if (byQid.size() > MAX_EXAMPLES) {
            throw new IllegalArgumentException("Choose at most " + MAX_EXAMPLES
                    + " example entities; " + byQid.size() + " were supplied.");
        }
        this.examples = List.copyOf(byQid.values());
        if (this.examples.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one example entity.");
        }
    }

    @Override public String purpose() { return "Find membership types from examples"; }
    @Override public String queryType() { return "SPARQL"; }
    @Override public String skeleton() {
        return "example QIDs --P31/P279*--> candidate membership types";
    }
    @Override public String description() {
        return "Read-only type discovery; only Use selected as membership type changes "
                + "class configuration.";
    }
    @Override public Map<String, String> parameters() {
        return Map.of("examples", Integer.toString(examples.size()),
                "candidateLimit", Integer.toString(MAX_CANDIDATES));
    }

    @Override public List<MembershipTypeCandidate> execute(QueryContext context)
            throws Exception {
        String sparql = sparql(examples, MAX_CANDIDATES);
        return context.step("Common membership types for " + examples.size() + " example(s)",
                "SPARQL", skeleton(), parameters(), step -> {
                    step.request(sparql);
                    List<MembershipTypeCandidate> result = new ArrayList<>();
                    Map<String, MembershipTypeCandidate.Member> byQid = new LinkedHashMap<>();
                    examples.forEach(example -> byQid.put(example.qid(), example));
                    for (WikidataBinding row : WikidataAccess
                            .sparql(context, Datasource.WIKIDATA).query(sparql)) {
                        String qid = row.qid("type");
                        if (!WikidataIds.isQid(qid)) continue;
                        List<MembershipTypeCandidate.Member> matched = qids(
                                row.value("matchedExamples")).stream()
                                .map(byQid::get).filter(java.util.Objects::nonNull)
                                .toList();
                        result.add(new MembershipTypeCandidate(
                                qid, row.value("typeLabel"),
                                MembershipTypeCandidate.Origin.COMMON_TYPE, "",
                                number(row.value("coverage")), examples.size(),
                                number(row.value("directCoverage")), -1, matched));
                    }
                    step.summary(result.size() + " candidate type(s)");
                    return List.copyOf(result);
                });
    }

    static String sparql(List<MembershipTypeCandidate.Member> examples, int limit) {
        String values = examples.stream().map(MembershipTypeCandidate.Member::qid)
                .filter(WikidataIds::isQid).distinct()
                .map(qid -> "wd:" + qid).collect(java.util.stream.Collectors.joining(" "));
        return """
                SELECT ?type ?typeLabel ?coverage ?directCoverage ?matchedExamples WHERE {
                  {
                    SELECT ?type
                           (COUNT(DISTINCT ?example) AS ?coverage)
                           (COUNT(DISTINCT ?directExample) AS ?directCoverage)
                           (GROUP_CONCAT(DISTINCT REPLACE(STR(?example),
                             "http://www.wikidata.org/entity/", ""); separator=" ")
                             AS ?matchedExamples)
                    WHERE {
                      VALUES ?example { %s }
                      ?example wdt:P31 ?directType .
                      ?directType wdt:P279* ?type .
                      OPTIONAL {
                        ?example wdt:P31 ?type .
                        BIND(?example AS ?directExample)
                      }
                    }
                    GROUP BY ?type
                    ORDER BY DESC(?coverage) DESC(?directCoverage)
                    LIMIT %d
                  }
                %s}
                ORDER BY DESC(?coverage) DESC(?directCoverage) ?typeLabel
                """.formatted(values, Math.max(1, limit),
                wikidata.query.LabelService.service(null, "type", "typeLabel"));
    }

    private static List<String> qids(String text) {
        if (text == null || text.isBlank()) return List.of();
        return java.util.Arrays.stream(text.trim().split("\\s+"))
                .filter(WikidataIds::isQid).distinct().toList();
    }

    private static int number(String value) {
        try { return value == null ? 0 : Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    @Override public int rowCount(List<MembershipTypeCandidate> result) {
        return result == null ? 0 : result.size();
    }
    @Override public String summary(List<MembershipTypeCandidate> result) {
        return rowCount(result) + " candidate membership type(s)";
    }
}
