package wikidata.explore.query.logical;

import wikidata.WikidataBinding;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.rule.RuleNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-target property profile for a MULTI-target membership: sample one instance
 * per membership target, then list each target's instance's properties (external
 * IDs dropped as noise). Grouped by target, this reveals the subclass structure —
 * e.g. Oscar person-categories (date of birth, spouse, occupation…) vs
 * film-categories (genre, award received…) cluster apart, surfacing Person vs
 * Film as the latent sub-structure of "nominee".
 *
 * <p>Two cheap, bounded steps (no full scan): (1) {@code GROUP BY ?target /
 * SAMPLE(?inst)} → one (target, instance) pair per target; (2) profile those
 * bounded pairs via {@code VALUES (?target ?inst) {…}} grouped by target+property.
 */
public class DiscoverPropertiesByTargetQuery implements Query<TableQueryResult> {

    // TargetQid is appended LAST so the leading [Target, Property, PID, Count]
    // columns stay positionally stable for SubclassClustering.clusterRows and the
    // panel's row parsing; the qid enables linking the target label + future
    // "materialise this cluster as an extends subclass".
    public static final List<String> COLUMNS =
            List.of("Target", "Property", "PID", "Count", "TargetQid");

    private final RuleNode node;
    private final int perTarget;

    public DiscoverPropertiesByTargetQuery(RuleNode node, int perTarget) {
        this.node = node;
        this.perTarget = Math.max(1, perTarget);
    }

    @Override public String purpose() {
        return "Discover properties by target";
    }

    @Override public String skeleton() {
        return "one instance per target -> per-target property profile (no external IDs)";
    }

    @Override public Map<String, String> parameters() {
        return Map.of("targets",
                node == null ? "0" : String.valueOf(node.allSourceQids().size()));
    }

    @Override public TableQueryResult execute(QueryContext context) throws Exception {
        String pid = node == null ? "" : RuleNode.cleanPid(node.propertyPid());
        List<String> targets = new ArrayList<>();
        if (node != null) {
            for (String t : node.allSourceQids()) {
                String q = RuleNode.cleanQid(t);
                if (q.matches("Q\\d+")) {
                    targets.add(q);
                }
            }
        }
        if (!pid.matches("P\\d+") || targets.size() < 2) {
            return new TableQueryResult(COLUMNS, List.of());
        }

        return context.step(
                "Properties by target",
                "SPARQL",
                null,
                Map.of("targets", String.valueOf(targets.size())),
                step -> {
                    StringBuilder vals = new StringBuilder();
                    for (String q : targets) {
                        vals.append("wd:").append(q).append(' ');
                    }
                    String triple = node.direction()
                            .triplePattern("?target", "?inst", pid);

                    // Collect each target's instances, then keep the first k per
                    // target client-side — SPARQL has no per-group LIMIT, so this
                    // is the cheap way to a bounded k-per-target sample.
                    // hint:optimizer "None" forces VALUES ?target to bind first
                    // so a generic membership predicate (e.g. P1411, shared by many
                    // award systems Wikidata-wide) is looked up per target instead
                    // of full-scanned — see RuleNodeQueryBuilder.stratifiedSampleQuery.
                    String s1 = "SELECT ?target"
                            + " (GROUP_CONCAT(DISTINCT ?inst; SEPARATOR=\" \") AS ?insts)"
                            + " WHERE {\n"
                            + "  hint:Query hint:optimizer \"None\" .\n"
                            + "  VALUES ?target { " + vals.toString().trim() + " }\n"
                            + "  " + triple + "\n"
                            + "} GROUP BY ?target";
                    step.subquery("instances per target (keep " + perTarget + ")",
                            s1, null);

                    StringBuilder pairs = new StringBuilder();
                    for (WikidataBinding b : context.sparql().query(s1)) {
                        String t = b.qid("target");
                        String insts = b.value("insts");
                        if (t == null || !t.matches("Q\\d+") || insts == null) {
                            continue;
                        }
                        int kept = 0;
                        for (String uri : insts.trim().split("\\s+")) {
                            if (kept >= perTarget) {
                                break;
                            }
                            int slash = uri.lastIndexOf('/');
                            String v = slash >= 0 ? uri.substring(slash + 1) : uri;
                            if (v.matches("Q\\d+")) {
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

                    // External-id properties are catalogue noise — drop them so the
                    // profile shows real attributes (the subclass signal).
                    String s2 = "SELECT ?target ?targetLabel ?prop ?propLabel"
                            + " (COUNT(DISTINCT ?inst) AS ?n) WHERE {\n"
                            + "  VALUES (?target ?inst) { " + pairs.toString().trim() + " }\n"
                            + "  ?inst ?dp ?o . ?prop wikibase:directClaim ?dp .\n"
                            + "  ?prop wikibase:propertyType ?pt . FILTER(?pt != wikibase:ExternalId)\n"
                            + "  ?prop rdfs:label ?propLabel . FILTER(LANG(?propLabel) = \"en\")\n"
                            + "  ?target rdfs:label ?targetLabel . FILTER(LANG(?targetLabel) = \"en\")\n"
                            + "} GROUP BY ?target ?targetLabel ?prop ?propLabel ORDER BY ?targetLabel DESC(?n)";
                    step.subquery("profile per target (no external IDs)", s2, null);

                    List<List<Object>> rows = new ArrayList<>();
                    for (WikidataBinding b : context.sparql().query(s2)) {
                        String prop = b.qid("prop");
                        String tQid = b.qid("target");
                        rows.add(List.of(
                                safe(b.value("targetLabel")),
                                safe(b.value("propLabel")),
                                prop == null ? "" : prop,
                                safe(b.value("n")),
                                tQid == null ? "" : tQid));
                    }
                    step.summary(rows.size() + " (target, property) rows");
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
