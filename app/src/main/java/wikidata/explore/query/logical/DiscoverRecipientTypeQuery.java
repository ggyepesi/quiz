package wikidata.explore.query.logical;

import wikidata.WikidataIds;

import wikidata.WikidataBinding;
import wikidata.explore.query.core.Datasource;
import work.Query;
import work.QueryContext;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.rule.RuleNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import wikidata.explore.query.core.WikidataAccess;

/**
 * Discover the recipient TYPE (instance-of, P31) per membership target, so the
 * subclass split is read directly off the data instead of inferred by profile
 * similarity: each Oscar category's recipients are P31 {@code human} (person
 * categories) or P31 {@code film} (film categories), and that discovered value
 * IS the subclass — keyed by a real QID (Q5 / Q11424) we can use as the
 * subclass's type, no threshold to tune.
 *
 * <p>Same two cheap bounded steps as {@link DiscoverPropertiesByTargetQuery}:
 * (1) {@code GROUP BY ?target / GROUP_CONCAT} → up to {@code perTarget} instances
 * per target; (2) for those bounded pairs, count each {@code ?inst wdt:P31 ?type}
 * grouped by target+type. A category usually resolves to one dominant type.
 */
public class DiscoverRecipientTypeQuery implements Query<TableQueryResult> {

    public static final List<String> COLUMNS =
            List.of("Target", "TargetQid", "Type", "TypeQid", "Count");

    private final RuleNode node;
    private final int perTarget;

    public DiscoverRecipientTypeQuery(RuleNode node, int perTarget) {
        this.node = node;
        this.perTarget = Math.max(1, perTarget);
    }

    @Override public String purpose() {
        return "Discover recipient type by target";
    }

    @Override public String skeleton() {
        return "k instances per target -> their P31 value(s), grouped by target";
    }

    @Override public Map<String, String> parameters() {
        return Map.of("targets",
                node == null ? "0" : String.valueOf(node.allSourceQids().size()),
                "perTarget", String.valueOf(perTarget));
    }

    @Override public TableQueryResult execute(QueryContext context) throws Exception {
        String pid = node == null ? "" : RuleNode.cleanPid(node.propertyPid());
        List<String> targets = new ArrayList<>();
        if (node != null) {
            for (String t : node.allSourceQids()) {
                String q = RuleNode.cleanQid(t);
                if (WikidataIds.isQid(q)) {
                    targets.add(q);
                }
            }
        }
        if (!WikidataIds.isPid(pid) || targets.size() < 2) {
            return new TableQueryResult(COLUMNS, List.of());
        }

        return context.step(
                "Recipient type by target",
                "SPARQL",
                null,
                Map.of("targets", String.valueOf(targets.size())),
                step -> {
                    String triple = node.direction()
                            .triplePattern("?target", "?inst", pid);

                    // hint:optimizer "None" forces VALUES ?target first so a generic
                    // membership predicate is looked up per target, not full-scanned
                    // (see RuleNodeQueryBuilder.stratifiedSampleQuery).
                    String s1 = "SELECT ?target"
                            + " (GROUP_CONCAT(DISTINCT ?inst; SEPARATOR=\" \") AS ?insts)"
                            + " WHERE {\n"
                            + "  hint:Query hint:optimizer \"None\" .\n"
                            + "  " + wikidata.explore.query.template.sparql.SparqlValues
                                    .clause("target", targets) + "\n"
                            + "  " + triple + "\n"
                            + "} GROUP BY ?target";
                    step.subquery("instances per target (keep " + perTarget + ")",
                            s1, null);

                    StringBuilder pairs = new StringBuilder();
                    for (WikidataBinding b : WikidataAccess.sparql(context, Datasource.WIKIDATA).query(s1)) {
                        String t = b.qid("target");
                        String insts = b.value("insts");
                        if (t == null || !WikidataIds.isQid(t) || insts == null) {
                            continue;
                        }
                        int kept = 0;
                        for (String uri : insts.trim().split("\\s+")) {
                            if (kept >= perTarget) {
                                break;
                            }
                            int slash = uri.lastIndexOf('/');
                            String v = slash >= 0 ? uri.substring(slash + 1) : uri;
                            if (WikidataIds.isQid(v)) {
                                pairs.append("(wd:").append(t)
                                     .append(" wd:").append(v).append(") ");
                                kept++;
                            }
                        }
                    }
                    if (pairs.length() == 0) {
                        step.summary("no instances");
                        return new TableQueryResult(COLUMNS, List.of());
                    }

                    String s2 = "SELECT ?target ?targetLabel ?type ?typeLabel"
                            + " (COUNT(DISTINCT ?inst) AS ?n) WHERE {\n"
                            + "  VALUES (?target ?inst) { " + pairs.toString().trim() + " }\n"
                            + "  ?inst wdt:P31 ?type .\n"
                            + "  ?type rdfs:label ?typeLabel . FILTER(LANG(?typeLabel) = \"en\")\n"
                            + "  ?target rdfs:label ?targetLabel . FILTER(LANG(?targetLabel) = \"en\")\n"
                            + "} GROUP BY ?target ?targetLabel ?type ?typeLabel"
                            + " ORDER BY ?targetLabel DESC(?n)";
                    step.subquery("recipient P31 per target", s2, null);

                    List<List<Object>> rows = new ArrayList<>();
                    for (WikidataBinding b : WikidataAccess.sparql(context, Datasource.WIKIDATA).query(s2)) {
                        String typeQid = b.qid("type");
                        String tQid = b.qid("target");
                        rows.add(List.of(
                                safe(b.value("targetLabel")),
                                tQid == null ? "" : tQid,
                                safe(b.value("typeLabel")),
                                typeQid == null ? "" : typeQid,
                                safe(b.value("n"))));
                    }
                    step.summary(rows.size() + " (target, type) rows");
                    return new TableQueryResult(COLUMNS, rows);
                });
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    @Override public int rowCount(TableQueryResult r) {
        return r == null ? 0 : r.size();
    }

    @Override public String summary(TableQueryResult r) {
        return rowCount(r) + " rows";
    }
}
