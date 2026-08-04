package wikidata.explore.query.template.rule;

import wikidata.WikidataIds;

import wikidata.explore.rule.RuleLabelConfig;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.filter.WikidataValueFilterSparql;
import wikidata.query.WikidataQueryBuilder;

import java.util.Collection;
import java.util.List;

/**
 * Single SPARQL factory for Rule Tree / Model Builder extraction and sampling.
 */
public final class RuleTreeQueries {

    private RuleTreeQueries() {
    }

    public static String valuesQuery(RuleNode node) {
        return valuesQueryForRoot(node, node.sourceQid(), true);
    }

    public static String valuesQueryWithoutIncludedFields(RuleNode node) {
        return valuesQueryForRoot(node, node.sourceQid(), false);
    }

    public static String valuesQueryForSpecificParent(
            RuleNode node,
            String parentQid,
            boolean includeIncludedFields) {

        return valuesQueryForRoot(node, parentQid, includeIncludedFields);
    }

    public static String batchedValuesQuery(
            RuleNode node,
            List<String> parentQids) {

        WikidataQueryBuilder q =
                new WikidataQueryBuilder()
                        .selectDistinct("?parent", "?value", "?valueLabel")
                        .valuesQids("root", parentQids)
                        .bind("?root", "parent");

        q.where(node.direction().triplePattern(
                "?root",
                "?value",
                RuleNode.cleanPid(node.propertyPid())));

        appendCommonFilters(q, node, "value");
        appendValueFilters(q, node);
        appendInlineIncludedFields(q, node);
        appendLabelPattern(q, "value", "valueLabel", node.labelConfig());

        return q.build();
    }

    public static String countNodeResultsQuery(RuleNode node) {
        WikidataQueryBuilder q =
                new WikidataQueryBuilder()
                        .countDistinct("value", "count")
                        .bindEntity("root", node.sourceQid());

        q.where(node.direction().triplePattern(
                "?root",
                "?value",
                RuleNode.cleanPid(node.propertyPid())));

        appendCommonFilters(q, node, "value");

        if (node.labelConfig() != null
                && node.labelConfig().requireLabel()) {
            appendLabelPattern(q, "value", "valueLabel", node.labelConfig());
        }

        return q.build();
    }

    public static String multiValueProbeQuery(
            RuleNode node,
            String propertyPid) {

        WikidataQueryBuilder q =
                new WikidataQueryBuilder()
                        .select("?value")
                        .selectRaw("(COUNT(DISTINCT ?field) AS ?n)")
                        .bindEntity("root", node.sourceQid());

        q.where(node.direction().triplePattern(
                "?root",
                "?value",
                RuleNode.cleanPid(node.propertyPid())));

        appendCommonFilters(q, node, "value");

        if (node.labelConfig() != null
                && node.labelConfig().requireLabel()) {
            appendLabelPattern(q, "value", "valueLabel", node.labelConfig());
        }

        q.truthy("value", propertyPid, "field");
        q.groupBy("value");
        q.having("?n > 1");
        q.limit(1);

        return q.build();
    }

    public static String countChildRowsQuery(
            RuleNode childNode,
            List<String> parentQids) {

        WikidataQueryBuilder q =
                new WikidataQueryBuilder()
                        .countAll("count")
                        .valuesQids("root", parentQids);

        q.where(childNode.direction().triplePattern(
                "?root",
                "?value",
                RuleNode.cleanPid(childNode.propertyPid())));

        appendCommonFilters(q, childNode, "value");

        if (childNode.labelConfig() != null
                && childNode.labelConfig().requireLabel()) {
            appendLabelPattern(q, "value", "valueLabel", childNode.labelConfig());
        }

        return q.build();
    }

    /**
     * Samples any direct field for a concrete parent batch.
     *
     * Works for:
     * - entity fields, e.g. P47 neighbours
     * - media fields, e.g. P18 image
     * - scalar fields
     */
    public static String fieldValueSampleQuery(
            RuleIncludedField field,
            Collection<String> parentQids,
            RuleLabelConfig labelConfig) {

        String var =
                RuleIncludedFieldSparql.variableName(field, 0);

        WikidataQueryBuilder q =
                new WikidataQueryBuilder()
                        .selectDistinct("?parent", "?" + var)
                        .valuesQids("parent", parentQids);

        q.truthy("parent", field.propertyPid(), var);

        if (!field.isMediaField()) {
            q.select(var + "Label");
            appendLabelPattern(q, var, var + "Label", labelConfig);
        }

        q.orderBy("parent");

        return q.build();
    }

    public static String fieldValueSampleQuery(
            RuleIncludedField field,
            Collection<String> parentQids,
            RuleLabelConfig labelConfig,
            int limit) {

        String var =
                RuleIncludedFieldSparql.variableName(field, 0);

        WikidataQueryBuilder q =
                new WikidataQueryBuilder()
                        .selectDistinct("?parent", "?" + var)
                        .valuesQids("parent", parentQids);

        q.truthy("parent", field.propertyPid(), var);

        if (!field.isMediaField()) {
            q.select(var + "Label");
            appendLabelPattern(q, var, var + "Label", labelConfig);
        }

        q.orderBy("parent");
        q.limit(limit);

        return q.build();
    }

    public static String delayedIncludedFieldQuery(
            RuleIncludedField field,
            Collection<String> parentQids,
            RuleLabelConfig labelConfig) {

        return fieldValueSampleQuery(field, parentQids, labelConfig);
    }

    private static String valuesQueryForRoot(
            RuleNode node,
            String rootQidOrVar,
            boolean includeIncludedFields) {

        boolean rootIsVariable =
                rootQidOrVar != null && rootQidOrVar.startsWith("?");

        WikidataQueryBuilder q =
                new WikidataQueryBuilder()
                        .selectDistinct("?value", "?valueLabel");

        if (rootIsVariable) {
            q.bind(rootQidOrVar, "root");
        } else {
            q.bindEntity("root", rootQidOrVar);
        }

        q.where(node.direction().triplePattern(
                "?root",
                "?value",
                RuleNode.cleanPid(node.propertyPid())));

        appendCommonFilters(q, node, "value");
        appendValueFilters(q, node);

        if (includeIncludedFields) {
            appendInlineIncludedFields(q, node);
        }

        appendLabelPattern(q, "value", "valueLabel", node.labelConfig());

        q.limit(node.limit());

        return RuleNodeQueryBuilder.sortAfterLimit(q.build(), "valueLabel");
    }

    private static void appendCommonFilters(
            WikidataQueryBuilder q,
            RuleNode node,
            String valueVar) {

        q.filterInQids(valueVar, node.includedQids());
        q.filterNotInQids(valueVar, node.excludedQids());

        if (node.excludedPredicateObjects() == null) {
            return;
        }

        for (RuleNode.PredicateObjectExclusion e
                : node.excludedPredicateObjects()) {

            String pid =
                    RuleNode.cleanPid(e.predicatePid());

            String qid =
                    RuleNode.cleanQid(e.objectQid());

            if (!WikidataIds.isPid(pid) || !WikidataIds.isQid(qid)) {
                continue;
            }

            q.filterNotExists(
                    "?" + valueVar + " wdt:"
                            + pid
                            + " wd:"
                            + qid
                            + " .");
        }
    }

    private static void appendValueFilters(
            WikidataQueryBuilder q,
            RuleNode node) {

        StringBuilder where =
                new StringBuilder();

        WikidataValueFilterSparql.appendWherePatterns(
                where,
                node.valueFilters());

        q.where(where.toString());

        StringBuilder select =
                new StringBuilder();

        WikidataValueFilterSparql.appendSelectVariables(
                select,
                node.valueFilters());

        for (String token : select.toString().trim().split("\\s+")) {
            if (!token.isBlank()) {
                q.select(token);
            }
        }
    }

    private static void appendInlineIncludedFields(
            WikidataQueryBuilder q,
            RuleNode node) {

        if (node.includedFields() == null || node.includedFields().isEmpty()) {
            return;
        }

        int index =
                0;

        for (RuleIncludedField field : node.includedFields()) {
            if (!isValidIncludedField(field)) {
                index++;
                continue;
            }

            if (field.isEntityField()) {
                index++;
                continue;
            }

            String var =
                    RuleIncludedFieldSparql.variableName(field, index);

            q.select(var);

            if (field.optional()) {
                q.optional("?value wdt:"
                        + field.propertyPid()
                        + " ?"
                        + var
                        + " .");
            } else {
                q.truthy("value", field.propertyPid(), var);
            }

            if (!field.isMediaField()) {
                q.select(var + "Label");
                appendLabelPattern(
                        q,
                        var,
                        var + "Label",
                        new RuleLabelConfig(false, "en"));
            }

            index++;
        }
    }

    public static boolean isDelayedIncludedField(RuleIncludedField field) {
        return isValidIncludedField(field) && field.isEntityField();
    }

    private static boolean isValidIncludedField(RuleIncludedField field) {
        return field != null
                && field.propertyPid() != null
                && WikidataIds.isPid(field.propertyPid())
                && field.fieldName() != null
                && !field.fieldName().isBlank();
    }

    public static void appendLabelPattern(
            WikidataQueryBuilder q,
            String itemVar,
            String labelVar,
            RuleLabelConfig labelConfig) {

        boolean required =
                labelConfig != null && labelConfig.requireLabel();

        String lang =
                labelConfig == null ? "en" : labelConfig.language();

        boolean any =
                lang == null
                        || lang.isBlank()
                        || "any".equalsIgnoreCase(lang);

        String triple =
                "?" + itemVar + " rdfs:label ?" + labelVar + " .";

        if (required) {
            q.where(triple);

            if (!any) {
                q.filterLang(labelVar, lang);
            }
        } else {
            if (any) {
                q.optional(triple);
            } else {
                q.optional(triple
                        + "\nFILTER(LANG(?"
                        + labelVar
                        + ") = \""
                        + WikidataQueryBuilder.sparqlString(lang)
                        + "\")");
            }
        }
    }
}
