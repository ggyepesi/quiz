package wikidata.explore.query.logical;

import wikidata.WikidataIds;

import wikidata.WikidataBinding;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.query.core.Datasource;
import work.Query;
import work.QueryContext;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.transform.QualifierLoadConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import wikidata.explore.query.core.WikidataAccess;

/**
 * Generic qualifier discovery for a RELATIONAL membership: sample some members,
 * read the membership relation's STATEMENTS (not just the direct claim), and list
 * the qualifier properties those statements carry, with coverage and an inferred
 * {@link QualifierLoadConfig.Kind} (from the qualifier's datatype). Domain-agnostic
 * — for Oscars (P1411 → categories) it finds for-work / year / nominee on its own;
 * for awards (P166) it would find year / for-work, for positions (P39)
 * start/end/district, etc. The discovered qualifiers feed a {@link
 * QualifierLoadConfig} (see {@link wikidata.explore.transform.QualifierLoadConfigs}).
 *
 * <p>Restricts the statements to the membership target set (the same QIDs that
 * define membership), so a generic relation like P1411 (also used by BAFTA/Globes)
 * only contributes its Oscar statements.
 */
public class DiscoverQualifiersQuery implements Query<TableQueryResult> {

    public static final List<String> COLUMNS =
            List.of("Qualifier", "PID", "Kind", "Count");

    private final RuleNode node;
    private final int sampleSize;

    public DiscoverQualifiersQuery(RuleNode node, int sampleSize) {
        this.node = node;
        this.sampleSize = Math.max(20, sampleSize);
    }

    @Override public String purpose() {
        return "Discover membership-relation qualifiers";
    }

    @Override public String skeleton() {
        return "sample members -> their relation statements' qualifiers (+kind, coverage)";
    }

    @Override public Map<String, String> parameters() {
        return Map.of("relation", node == null ? "" : RuleNode.cleanPid(node.propertyPid()),
                "sample", String.valueOf(sampleSize));
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
        // Only the ITEM_TO_ROOT relational case (the entity holds the relation,
        // e.g. nominee --P1411--> category); P31 isn't a relation worth probing.
        if (!WikidataIds.isPid(pid) || pid.equals("P31") || targets.isEmpty()
                || node.direction() != RuleDirection.ITEM_TO_ROOT) {
            return new TableQueryResult(COLUMNS, List.of());
        }

        return context.step(
                "Discover qualifiers",
                "SPARQL",
                null,
                Map.of("relation", pid, "targets", String.valueOf(targets.size())),
                step -> {
                    StringBuilder vals = new StringBuilder();
                    for (String q : targets) {
                        vals.append("wd:").append(q).append(' ');
                    }
                    String targetVals = vals.toString().trim();

                    // hint:optimizer "None" → bind the sampled members first so the
                    // generic predicate is looked up per member, not full-scanned.
                    String sparql = "SELECT ?qualLabel ?qual ?ptype"
                            + " (COUNT(DISTINCT ?st) AS ?n) WHERE {\n"
                            + "  { SELECT ?e WHERE {\n"
                            + "      hint:Query hint:optimizer \"None\" .\n"
                            + "      VALUES ?t0 { " + targetVals + " }\n"
                            + "      ?e wdt:" + pid + " ?t0 .\n"
                            + "    } LIMIT " + sampleSize + " }\n"
                            + "  ?e p:" + pid + " ?st . ?st ps:" + pid + " ?v .\n"
                            + "  VALUES ?v { " + targetVals + " }\n"
                            + "  ?st ?pq ?qv .\n"
                            + "  ?qual wikibase:qualifier ?pq .\n"
                            + "  ?qual wikibase:propertyType ?ptype .\n"
                            + "  ?qual rdfs:label ?qualLabel . FILTER(LANG(?qualLabel) = \"en\")\n"
                            + "} GROUP BY ?qualLabel ?qual ?ptype ORDER BY DESC(?n)";
                    step.request(sparql);

                    List<List<Object>> rows = new ArrayList<>();
                    for (WikidataBinding b : WikidataAccess.sparql(context, Datasource.WIKIDATA).query(sparql)) {
                        String qual = b.qid("qual");
                        if (qual == null || !WikidataIds.isPid(qual)) {
                            continue;
                        }
                        rows.add(List.of(
                                safe(b.value("qualLabel")),
                                qual,
                                kindFor(b.value("ptype")).name(),
                                safe(b.value("n"))));
                    }
                    step.summary(rows.size() + " qualifiers");
                    return new TableQueryResult(COLUMNS, rows);
                });
    }

    /** Infer the load kind from a Wikibase {@code propertyType} URI. */
    public static QualifierLoadConfig.Kind kindFor(String propertyTypeUri) {
        if (propertyTypeUri == null) {
            return QualifierLoadConfig.Kind.STRING;
        }
        if (propertyTypeUri.endsWith("WikibaseItem")) {
            return QualifierLoadConfig.Kind.ENTITY;
        }
        if (propertyTypeUri.endsWith("Time")) {
            // DATE, not YEAR: a time qualifier is a time, and FlexibleDate keeps
            // exactly the precision the value states — so DATE is faithful where
            // YEAR forces a year even onto a value that named a day. Reducing a
            // date to its year stays available, but as a deliberate choice.
            return QualifierLoadConfig.Kind.DATE;
        }
        return QualifierLoadConfig.Kind.STRING;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    @Override public int rowCount(TableQueryResult r) {
        return r == null ? 0 : r.size();
    }

    @Override public String summary(TableQueryResult r) {
        return rowCount(r) + " qualifiers";
    }
}
