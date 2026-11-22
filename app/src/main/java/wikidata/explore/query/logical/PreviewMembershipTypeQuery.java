package wikidata.explore.query.logical;

import wikidata.WikidataBinding;
import wikidata.WikidataIds;
import wikidata.query.core.Datasource;
import wikidata.query.core.WikidataAccess;
import wikidata.explore.query.result.MembershipTypeCandidate;
import wikidata.explore.query.result.MembershipTypePreview;
import wikidata.query.template.rule.RuleNodeQueryBuilder;
import domain.compile.rule.RuleNode;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Counts to a visible ceiling and shows complete small or capped large memberships.
 *
 * <p>With the selected class's compiled rule, it also counts how many of those members
 * the class itself would generate: the candidate type replaces the class's membership
 * and generation's own root-membership query runs bounded. The class's filters are
 * therefore never restated here; whatever generation applies, this count applies.
 */
public final class PreviewMembershipTypeQuery implements Query<MembershipTypePreview> {
    public static final int COUNT_BOUND = 1_000;
    public static final int MEMBER_LIMIT = 200;
    private final String qid;
    private final String label;
    private final boolean includeSubclasses;
    private final RuleNode classRule;

    public PreviewMembershipTypeQuery(String qid, boolean includeSubclasses) {
        this(qid, "", includeSubclasses);
    }

    public PreviewMembershipTypeQuery(String qid, String label,
            boolean includeSubclasses) {
        this(qid, label, includeSubclasses, null);
    }

    /** {@code classRule} is the selected class's compiled rule, or null for none. */
    public PreviewMembershipTypeQuery(String qid, String label,
            boolean includeSubclasses, RuleNode classRule) {
        if (!WikidataIds.isQid(qid)) throw new IllegalArgumentException("Choose a type QID.");
        this.qid = qid;
        this.label = label == null ? "" : label;
        this.includeSubclasses = includeSubclasses;
        this.classRule = classRule;
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
        MembershipTypePreview.ClassFilters classFilters = classRule == null || bounded == 0
                ? null : classFilters(context);
        return new MembershipTypePreview(qid, label, includeSubclasses,
                Math.min(bounded, COUNT_BOUND), bounded > COUNT_BOUND, members,
                classFilters);
    }

    private MembershipTypePreview.ClassFilters classFilters(QueryContext context)
            throws Exception {
        RuleNode rule = candidatePopulationRule(classRule, qid, includeSubclasses);
        List<String> filters = populationFilters(rule);
        if (filters.isEmpty()) {
            return new MembershipTypePreview.ClassFilters(classRule.name(), filters,
                    classRule.limit(), -1, false, Set.of());
        }
        String request = RuleNodeQueryBuilder.valuesQuery(rule);
        return context.step("Count members of " + qid + " that pass "
                        + classRule.name() + "'s population filters, up to " + COUNT_BOUND,
                "SPARQL", "generation's root membership query with " + qid
                        + " as the membership type",
                Map.of("typeQid", qid, "class", classRule.name(),
                        "filters", String.join("; ", filters),
                        "countBound", Integer.toString(COUNT_BOUND)), step -> {
                    step.request(request);
                    List<WikidataBinding> rows = WikidataAccess
                            .sparql(context, Datasource.WIKIDATA).query(request);
                    Set<String> passing = new LinkedHashSet<>();
                    for (WikidataBinding row : rows) {
                        String member = row.qid("value");
                        if (WikidataIds.isQid(member)) passing.add(member);
                    }
                    // A row limit, not a member limit: one member may occupy several
                    // label rows, so reaching it means the count may be incomplete.
                    boolean capped = rows.size() > COUNT_BOUND;
                    int count = Math.min(passing.size(), COUNT_BOUND);
                    step.summary((capped ? count + "+" : Integer.toString(count))
                            + " member(s) pass " + String.join(", ", filters));
                    return new MembershipTypePreview.ClassFilters(classRule.name(),
                            filters, classRule.limit(), count, capped,
                            capped ? Set.of() : passing);
                });
    }

    /**
     * The class's rule as generation would run it had {@code typeQid} been its
     * membership type. Everything else the class authored stays: required fields,
     * label and sitelink requirements, allowed and excluded QIDs, type exclusions,
     * discriminator and value filters. Ranking is dropped because it only chooses which
     * members fill the class limit; it never removes one (a missing measure is
     * OPTIONAL), and the limit is reported separately. The limit becomes the count
     * ceiling.
     */
    static RuleNode candidatePopulationRule(RuleNode classRule, String typeQid,
            boolean includeSubclasses) {
        RuleNode rule = classRule.backboneCopy(COUNT_BOUND + 1);
        rule.sourceQid(typeQid);
        rule.additionalSourceQids().clear();
        rule.propertyPid(domain.config.MembershipPattern.INSTANCE_OF);
        rule.direction(domain.config.RuleDirection.ITEM_TO_ROOT);
        rule.membershipIncludesDescendants(includeSubclasses);
        rule.membershipQualifierCondition(null);
        rule.rankBySitelinks(false);
        rule.rankPropertyPid("");
        rule.rankCondition(null);
        rule.rankBand(RuleNode.UNBOUNDED, RuleNode.UNBOUNDED);
        return rule;
    }

    /** Names the population filters a rule applies besides its membership type. */
    static List<String> populationFilters(RuleNode rule) {
        List<String> filters = new ArrayList<>();
        if (rule.labelConfig() != null && rule.labelConfig().requireLabel()) {
            String language = rule.labelConfig().language();
            filters.add("label required"
                    + (language == null || language.isBlank() ? "" : " (" + language + ")"));
        }
        if (rule.requireSitelink()) filters.add("English Wikipedia article required");
        for (var field : rule.membershipConstraints()) {
            filters.add("required field " + field.fieldName()
                    + " (" + RuleNode.cleanPid(field.propertyPid()) + ")");
        }
        if (rule.hasMembershipFilter()) {
            filters.add("also " + RuleNode.cleanPid(rule.membershipPid())
                    + (rule.membershipQid() == null || rule.membershipQid().isBlank()
                            ? " (qualified)" : " " + rule.membershipQid()));
        }
        if (!rule.includedQids().isEmpty()) {
            filters.add("only " + rule.includedQids().size() + " allowed QID(s)");
        }
        if (!rule.excludedQids().isEmpty()) {
            filters.add(rule.excludedQids().size() + " excluded QID(s)");
        }
        for (RuleNode.PredicateObjectExclusion exclusion : rule.excludedPredicateObjects()) {
            filters.add("excludes " + exclusion.predicatePid() + " " + exclusion.objectQid());
        }
        for (var filter : rule.valueFilters()) {
            filters.add("value filter on " + (filter.fieldName() == null
                    || filter.fieldName().isBlank()
                    ? RuleNode.cleanPid(filter.propertyPid()) : filter.fieldName()));
        }
        return List.copyOf(filters);
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
        MembershipTypePreview.ClassFilters filters = result.classFilters();
        return (result.countCapped() ? result.boundedCount() + "+" : result.boundedCount())
                + " members; " + (complete ? "showing all " : "showing ")
                + result.members().size()
                + (filters == null || !filters.checked() ? ""
                        : "; " + (filters.capped() ? filters.count() + "+" : filters.count())
                                + " pass " + filters.className() + "'s filters");
    }
}
