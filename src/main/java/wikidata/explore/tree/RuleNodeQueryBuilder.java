package wikidata.explore.tree;

import wikidata.explore.filter.WikidataValueFilterSparql;
import wikidata.query.WikidataQueryBuilder;

import java.util.List;

/**
 * Builds all SPARQL queries for a RuleNode.
 *
 * Adds field-based GROUP_CONCAT optimization for simple Entity/Object List fields.
 */
public final class RuleNodeQueryBuilder {

    private static final String PAIR_SEPARATOR = "§";

    private RuleNodeQueryBuilder() {}

    public static String valuesQuery(RuleNode node) {
        return valuesQueryForNode(node, node.sourceQid(), List.of());
    }

    public static String valuesQueryForSpecificParent(
            RuleNode node, String parentQid) {
        return valuesQueryForNode(node, parentQid, List.of());
    }

    public static String fieldOptimizedValuesQuery(RuleNode node) {
        List<RuleIncludedField> inlined =
                RuleTreeExtractor.simpleInlinedFields(node);

        if (inlined.isEmpty()) {
            return valuesQuery(node);
        }

        return valuesQueryForNode(node, node.sourceQid(), inlined);
    }

    private static String valuesQueryForNode(
            RuleNode node,
            String rootQidOrVar,
            List<RuleIncludedField> inlinedFields) {

        String pid = RuleNode.cleanPid(node.propertyPid());
        boolean isVariable = rootQidOrVar != null
                && rootQidOrVar.startsWith("?");

        WikidataQueryBuilder q = new WikidataQueryBuilder();

        q.select("value", "valueLabel");
        appendValueFilterSelects(q, node);
        appendIncludedFieldSelects(q, node, inlinedFields);

        for (RuleIncludedField field : inlinedFields) {
            q.groupConcat(
                    inlinedPairVar(field),
                    RuleTreeExtractor.inlinedFieldAlias(field),
                    "|");
        }

        if (!isVariable) {
            q.bindEntity("root", rootQidOrVar);
        } else {
            q.rawWhere("# template: ?root supplied by VALUES clause at runtime");
        }

        q.rawWhere(node.direction().triplePattern("?root", "?value", pid));

        appendAllowedQids(q, node);
        appendExcludedQids(q, node);
        appendPredicateObjectExclusions(q, node);
        appendValueFilterPatterns(q, node);
        appendIncludedFieldPatterns(q, node, inlinedFields);
        appendInlinedFieldPatterns(q, inlinedFields);
        appendLabelPattern(q, node);

        if (inlinedFields.isEmpty()) {
            q.orderBy("valueLabel").limit(node.limit());
        } else {
            q.groupBy("value", "valueLabel");
            q.orderBy("valueLabel");
            q.limit(node.limit());
        }

        return (isVariable
                ? "# NOTE: query template — ?root replaced by VALUES at runtime.\n\n"
                : "")
                + q.build();
    }

    public static String batchedValuesQuery(
            RuleNode node, List<String> parentQids) {

        String pid = RuleNode.cleanPid(node.propertyPid());

        WikidataQueryBuilder q = new WikidataQueryBuilder();

        q.select("parent", "value", "valueLabel");
        appendValueFilterSelects(q, node);
        appendIncludedFieldSelects(q, node, List.of());

        q.valuesQids("root", parentQids);
        q.bind("?root", "parent");

        q.rawWhere(node.direction().triplePattern("?root", "?value", pid));

        appendAllowedQids(q, node);
        appendExcludedQids(q, node);
        appendPredicateObjectExclusions(q, node);
        appendValueFilterPatterns(q, node);
        appendIncludedFieldPatterns(q, node, List.of());
        appendLabelPattern(q, node);

        return q.build();
    }

    public static String inlinedValuesQuery(
            RuleNode node, List<RuleEdge> simpleEdges) {
        // Keep old method available, but the new path is fieldOptimizedValuesQuery.
        return valuesQuery(node);
    }

    private static String inlinedPairVar(RuleIncludedField field) {
        return field.fieldName().replaceAll("[^A-Za-z0-9_]", "_") + "_pair";
    }

    private static String inlinedValueVar(RuleIncludedField field) {
        return "?" + field.fieldName().replaceAll("[^A-Za-z0-9_]", "_") + "_gc";
    }

    private static String inlinedLabelVar(RuleIncludedField field) {
        return "?" + field.fieldName().replaceAll("[^A-Za-z0-9_]", "_") + "_gcLabel";
    }

    private static String inlinedConcatExpression(RuleIncludedField field) {
        return "CONCAT(STR(" + inlinedValueVar(field) + "), \""
                + PAIR_SEPARATOR
                + "\", COALESCE("
                + inlinedLabelVar(field)
                + ", \"\"))";
    }

    private static void appendInlinedFieldPatterns(
            WikidataQueryBuilder q,
            List<RuleIncludedField> inlinedFields) {

        for (RuleIncludedField field : inlinedFields) {
            String pid = RuleNode.cleanPid(field.propertyPid());
            String valueVar = inlinedValueVar(field);
            String labelVar = inlinedLabelVar(field);
            String pairVar = inlinedPairVar(field);

            q.rawWhere("""
                OPTIONAL {
                  ?value wdt:%s %s .
                  OPTIONAL {
                    %s rdfs:label %s .
                    FILTER(LANG(%s) = "en")
                  }
                  BIND(CONCAT(STR(%s), "§", COALESCE(%s, "")) AS ?%s)
                }
                """.formatted(
                    pid,
                    valueVar,
                    valueVar,
                    labelVar,
                    labelVar,
                    valueVar,
                    labelVar,
                    pairVar));
        }
    }

    private static void appendValueFilterSelects(
            WikidataQueryBuilder q, RuleNode node) {
        StringBuilder sb = new StringBuilder();
        WikidataValueFilterSparql.appendSelectVariables(sb, node.valueFilters());
        for (String token : sb.toString().trim().split("\\s+"))
            if (!token.isBlank()) q.select(token);
    }

    private static void appendIncludedFieldSelects(
            WikidataQueryBuilder q,
            RuleNode node,
            List<RuleIncludedField> skipFields) {

        if (node.includedFields().isEmpty()) return;
        int index = 0;
        for (RuleIncludedField field : node.includedFields()) {
            if (field == null) { index++; continue; }
            if (skipFields.contains(field)) { index++; continue; }

            String var = RuleIncludedFieldSparql.variableName(field, index);
            q.select(var);
            if (!field.isMediaField()) q.select(var + "Label");
            index++;
        }
    }

    private static void appendLabelPattern(
            WikidataQueryBuilder q, RuleNode node) {

        RuleLabelConfig cfg = node.labelConfig();
        boolean required = cfg != null && cfg.requireLabel();
        String lang = cfg == null ? "en" : cfg.language();

        q.rdfsLabelPattern("value", lang, required);
    }

    private static void appendAllowedQids(
            WikidataQueryBuilder q, RuleNode node) {
        if (node.includedQids().isEmpty()) return;
        q.filterInAsValues("value", node.includedQids());
    }

    private static void appendExcludedQids(
            WikidataQueryBuilder q, RuleNode node) {
        if (node.excludedQids().isEmpty()) return;
        q.filterNotInQids("value", node.excludedQids());
    }

    private static void appendPredicateObjectExclusions(
            WikidataQueryBuilder q, RuleNode node) {
        for (RuleNode.PredicateObjectExclusion e
                : node.excludedPredicateObjects()) {
            String pid = RuleNode.cleanPid(e.predicatePid());
            String qid = RuleNode.cleanQid(e.objectQid());
            if (!pid.matches("P\\d+") || !qid.matches("Q\\d+")) continue;
            q.filterNotExists("?value wdt:" + pid + " wd:" + qid + " .");
        }
    }

    private static void appendValueFilterPatterns(
            WikidataQueryBuilder q, RuleNode node) {
        if (node.valueFilters().isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        WikidataValueFilterSparql.appendWherePatterns(sb, node.valueFilters());
        if (!sb.isEmpty()) q.rawWhere(sb.toString());
    }

    private static void appendIncludedFieldPatterns(
            WikidataQueryBuilder q,
            RuleNode node,
            List<RuleIncludedField> skipFields) {

        if (node.includedFields().isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        List<RuleIncludedField> filtered =
                node.includedFields().stream()
                        .filter(f -> f != null && !skipFields.contains(f))
                        .toList();

        RuleIncludedFieldSparql.appendWherePatterns(sb, filtered);
        if (!sb.isEmpty()) q.rawWhere(sb.toString());
    }
}
