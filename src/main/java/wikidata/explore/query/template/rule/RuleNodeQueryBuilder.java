package wikidata.explore.query.template.rule;

import wikidata.explore.rule.RuleLabelConfig;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.rule.RuleEdge;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.filter.WikidataValueFilterSparql;
import wikidata.query.WikidataQueryBuilder;

import java.util.ArrayList;
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
        List<RuleIncludedField> inlined = simpleInlinedFields(node);

        if (inlined.isEmpty()) {
            return valuesQuery(node);
        }

        return valuesQueryForNode(node, node.sourceQid(), inlined);
    }

    public static List<RuleIncludedField> simpleInlinedFields(RuleNode node) {
        List<RuleIncludedField> out = new ArrayList<>();
        if (node == null) return out;

        for (RuleIncludedField field : node.includedFields()) {
            if (isInlineableEntityListField(field)) {
                out.add(field);
            }
        }

        return out;
    }

    /** Inline only Entity/Object + List fields. */
    public static boolean isInlineableEntityListField(RuleIncludedField field) {
        if (field == null) return false;
        if (field.isMediaField()) return false;

        String pid = RuleNode.cleanPid(field.propertyPid());
        if (!pid.matches("P\\d+")) return false;

        return field.collection() && field.isEntityField();
    }

    public static String inlinedFieldAlias(RuleIncludedField field) {
        return field.fieldName().replaceAll("[^A-Za-z0-9_]", "_") + "_inlined";
    }

    private static String valuesQueryForNode(
            RuleNode node,
            String rootQidOrVar,
            List<RuleIncludedField> inlinedFields) {

        String pid = RuleNode.cleanPid(node.propertyPid());
        boolean isVariable = rootQidOrVar != null
                && rootQidOrVar.startsWith("?");

        // Always label INLINE, never via SERVICE. Wrapping a big-class subquery
        // (e.g. Q523 "star", ~3M) in `SERVICE wikibase:label` makes Blazegraph
        // flatten it and re-scan the whole class — even with an inner LIMIT —
        // and it times out. An inline `?value rdfs:label ?l . FILTER(LANG=...)`
        // streams WITH the LIMIT (stops after `limit` rows) and, when the label
        // is required, also restricts the scan to named entities. (Measured:
        // inner alone ~1s; + SERVICE ~60s; + inline label ~1s.)
        boolean useService = false;

        // A value filter and an included field on the SAME property (e.g.
        // apparentMagnitude ≤6 + the magnitude value) would bind the property
        // twice and cross-product (R13). Reuse the included field's var for the
        // filter and skip that field's own pattern.
        java.util.Map<String, String> sharedVars =
                sharedFilterVars(node, inlinedFields, "");
        List<RuleIncludedField> patternSkip = new ArrayList<>(inlinedFields);
        for (RuleIncludedField f : node.includedFields())
            if (f != null && sharedVars.containsKey(f.fieldName())) patternSkip.add(f);

        WikidataQueryBuilder q = new WikidataQueryBuilder();

        q.select("value");
        if (!useService) q.select("valueLabel");
        appendValueFilterSelects(q, node, sharedVars.keySet());
        appendIncludedFieldSelects(q, node, inlinedFields, !useService);

        for (RuleIncludedField field : inlinedFields) {
            q.groupConcat(
                    inlinedPairVar(field),
                    inlinedFieldAlias(field),
                    "|");
        }

        if (!isVariable) {
            // Multi-QID membership: instance-of ANY of the listed types (e.g.
            // constellation OR zodiacal constellation). A single QID stays a
            // plain BIND.
            if (!node.additionalSourceQids().isEmpty()) {
                q.valuesQids("root", node.allSourceQids());
            } else {
                q.bindEntity("root", rootQidOrVar);
            }
        } else {
            q.rawWhere("# template: ?root supplied by VALUES clause at runtime");
        }

        q.rawWhere(node.direction().triplePattern("?root", "?value", pid));
        appendMembershipFilter(q, node);
        appendSitelinkRequirement(q, node);

        appendAllowedQids(q, node);
        appendExcludedQids(q, node);
        appendPredicateObjectExclusions(q, node);
        appendValueFilterPatterns(q, node, sharedVars);
        appendIncludedFieldPatterns(q, node, patternSkip, !useService);
        appendInlinedFieldPatterns(q, inlinedFields);
        if (!useService) appendLabelPattern(q, node);

        if (inlinedFields.isEmpty()) {
            q.limit(node.limit());
        } else {
            // GROUP_CONCAT folds the inlined collection(s); every other
            // selected scalar (value, its label, and each non-inlined field +
            // label) is non-aggregate and so must appear in GROUP BY, or
            // Blazegraph rejects the query ("Non-aggregate variable in select
            // expression").
            q.groupByNonAggregateSelects();
            q.limit(node.limit());
        }

        String prefix = isVariable
                ? "# NOTE: query template — ?root replaced by VALUES at runtime.\n\n"
                : "";
        return prefix + (useService
                ? sortAfterLimitWithLabelService(q.build(), "valueLabel", labelLanguage(node))
                : sortAfterLimit(q.build(), "valueLabel"));
    }

    // Outer wrapper that labels the limited rows via the SERVICE (no inline
    // rdfs:label scan), then orders.
    private static String sortAfterLimitWithLabelService(
            String innerQuery, String orderVar, String lang) {
        return "SELECT * WHERE {\n  {\n"
                + innerQuery.indent(4)
                + "  }\n"
                + labelService(lang)
                + "}\nORDER BY ?" + orderVar + "\n";
    }

    // Manual-mode wikibase:label binding. The automatic mode only emits ?xLabel
    // for variables *named* in the SELECT clause; these wrappers use SELECT *,
    // so automatic mode produced no ?valueLabel at all and every value rendered
    // as its bare QID. Binding ?value rdfs:label ?valueLabel inside the service
    // forces the label into the projection (with the service's QID fallback for
    // unlabelled entities, so it never drops rows).
    private static String labelService(String lang) {
        // Fall back to "mul" (Wikidata's "default for all languages" label) when
        // the requested language is missing. Many entities — e.g. star Q14044,
        // whose only Latin-script name "Albaldah" lives in mul, not en — have no
        // en label at all and would otherwise render as a bare QID.
        String fallback = lang.contains(",") ? lang : lang + ",mul";
        return "  SERVICE wikibase:label {\n"
                + "    bd:serviceParam wikibase:language \"" + fallback + "\".\n"
                + "    ?value rdfs:label ?valueLabel.\n"
                + "  }\n";
    }

    /**
     * Orders the limited rows without flattening, via {@link #namedSubquerySort}.
     * Safe now that the value-filter/included-field double-bind is deduped (R13):
     * the inner is efficient, so forcing it to materialise first is cheap (~2s)
     * and we keep alphabetical order. A plain outer {@code ORDER BY} would flatten
     * + re-scan (R11).
     */
    public static String sortAfterLimit(
            String innerQuery,
            String orderVar) {

        return namedSubquerySort(innerQuery, orderVar);
    }

    /**
     * Order a limited subquery WITHOUT flattening it, via a Blazegraph NAMED
     * SUBQUERY ({@code WITH { … } AS %r WHERE { INCLUDE %r } ORDER BY …}). The
     * inner LIMIT is guaranteed to run first, so only those rows are sorted.
     * Validated (~2s, alphabetical) — but see R13: it forces full inner
     * materialisation, so use it only when the inner is efficient. {@code WITH …
     * AS} goes BETWEEN SELECT and WHERE. (WDQS optimization guide, "named
     * subqueries".)
     */
    public static String namedSubquerySort(
            String innerQuery,
            String orderVar) {

        return "SELECT *\n"
                + "WITH {\n"
                + innerQuery.indent(2)
                + "} AS %limited\n"
                + "WHERE { INCLUDE %limited }\n"
                + "ORDER BY ?" + orderVar + "\n";
    }

    /** Preview of the runtime batched child query (the one actually executed),
     *  with a placeholder VALUES list — so "Show query logs" reflects what runs
     *  (incl. the label SERVICE), not a different template. */
    public static String batchedValuesQueryPreview(RuleNode node) {
        return "# NOTE: the ?root VALUES are the parent QIDs, supplied at runtime.\n\n"
                + batchedValuesQuery(node, List.of("Q0"))
                .replace("wd:Q0", "/* parent QIDs */");
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
        appendMembershipFilter(q, node);
        appendSitelinkRequirement(q, node);

        appendAllowedQids(q, node);
        appendExcludedQids(q, node);
        appendPredicateObjectExclusions(q, node);
        appendValueFilterPatterns(q, node);
        // No inline rdfs:label for fields — the label SERVICE below provides
        // every ?xLabel (an inline one would clash with the service).
        appendIncludedFieldPatterns(q, node, List.of(), false);

        // Use the Wikidata label SERVICE instead of an inline
        // "?value rdfs:label ?valueLabel . FILTER(LANG=...)". For a batched
        // child edge (e.g. stars across every constellation) the inline pattern
        // forces a full label scan over a huge candidate set and times out; the
        // service labels only the matched rows. (?valueLabel is provided by the
        // service.)
        q.serviceLabel(labelLanguage(node));

        return q.build();
    }

    /**
     * Child query for ONE parent, with a per-parent LIMIT applied BEFORE
     * labelling. The batched (multi-parent) query can't express a per-parent
     * limit, so for a heavy edge (e.g. a constellation's stars) it pulls every
     * candidate and times out. This limits the children to {@code limit} first,
     * then labels just those via the SERVICE. Selects only {@code ?value} (the
     * parent is known by the caller).
     */
    public static String childQueryForParent(
            RuleNode node, String parentQid, int limit) {

        String pid = RuleNode.cleanPid(node.propertyPid());
        String parentTerm = "wd:" + RuleNode.cleanQid(parentQid);
        String lang = labelLanguage(node);
        boolean requireLabel =
                node.labelConfig() != null && node.labelConfig().requireLabel();

        // ONE grouped query, SAMPLEing one representative value per included
        // field so there is exactly one row per entity — this is what makes the
        // per-parent LIMIT count DISTINCT entities. (A multi-valued field like
        // P1215, a star's several magnitude measurements, otherwise cross-
        // products the row LIMIT: Antlia gave 2 stars instead of 6.) It must be
        // a single grouped query, NOT an inner-DISTINCT subquery + outer re-
        // fetch: any outer triple pattern on ?value makes Blazegraph re-scan the
        // whole candidate set and time out (~65s).
        //
        // The label is bound inline and, when required, as a hard FILTER. For a
        // popular relation (P59 -> a constellation's ~20-30k catalog objects)
        // that restricts the scan to the handful of named entities — the
        // difference between ~7s and a 60s timeout — and it honours requireLabel
        // (the old SERVICE path labelled without restricting, so it scanned
        // everything and let unlabelled catalog stars through as bare QIDs).
        WikidataQueryBuilder q = new WikidataQueryBuilder();
        q.distinct(false);
        q.select("value");
        q.selectRaw("(SAMPLE(?valueLabel_s) AS ?valueLabel)");

        // Sort the entities by one included field before the LIMIT (e.g.
        // brightest stars first), so the per-parent limit keeps the brightest N
        // rather than an arbitrary in-range set. The sort field is aggregated
        // with MIN (ascending) / MAX (descending) — both the value to order by
        // AND the value shown — so e.g. a star's brightest magnitude drives the
        // ranking; other fields stay SAMPLEd.
        String sortField = node.hasSort() ? node.sortFieldName() : null;
        boolean sortDesc = node.sortDescending();
        String sortVar = null;

        // Dedup (R13): a value filter on the same property as an included field
        // binds that field's SAMPLE-source var (?<var>_s) instead of a second
        // ?value wdt:Pxx ?other that cross-products; skip the field's own triple.
        java.util.Map<String, String> sharedVars =
                sharedFilterVars(node, java.util.List.of(), "_s");

        StringBuilder includedPatterns = new StringBuilder();
        int index = 0;
        for (RuleIncludedField f : node.includedFields()) {
            if (f != null && f.propertyPid() != null && !f.propertyPid().isBlank()) {
                String var = RuleIncludedFieldSparql.variableName(f, index);
                boolean isSort = sortField != null && sortField.equals(f.fieldName());
                String agg = isSort ? (sortDesc ? "MAX" : "MIN") : "SAMPLE";
                q.selectRaw("(" + agg + "(?" + var + "_s) AS ?" + var + ")");
                if (isSort) sortVar = var;
                // When the value filter binds this var (?<var>_s), don't emit the
                // field's own binding too.
                if (!sharedVars.containsKey(f.fieldName())) {
                    String triple = "  ?value wdt:" + RuleNode.cleanPid(f.propertyPid())
                            + " ?" + var + "_s .\n";
                    includedPatterns.append(
                            f.optional() ? "  OPTIONAL {\n  " + triple + "  }\n" : triple);
                }
            }
            index++;
        }

        q.rawWhere(node.direction().triplePattern(parentTerm, "?value", pid));
        appendMembershipFilter(q, node);
        appendSitelinkRequirement(q, node);
        appendAllowedQids(q, node);
        appendExcludedQids(q, node);
        appendPredicateObjectExclusions(q, node);
        appendValueFilterPatterns(q, node, sharedVars);   // shared -> bind ?<var>_s
        if (includedPatterns.length() > 0) {
            q.rawWhere(includedPatterns.toString());
        }

        if (requireLabel) {
            q.rawWhere("  ?value rdfs:label ?valueLabel_s . FILTER(LANG(?valueLabel_s) = \""
                    + lang + "\")");
        } else {
            // No hard requirement: still surface a name, with the mul fallback.
            q.rawWhere("  OPTIONAL { ?value rdfs:label ?valueLabel_s ."
                    + " FILTER(LANG(?valueLabel_s) IN (\"" + lang + "\", \"mul\")) }");
        }

        q.groupBy("value");
        if (sortVar != null) {
            q.orderByRaw((sortDesc ? "DESC(?" : "ASC(?") + sortVar + ")");
        }
        if (limit > 0) {
            q.limit(limit);
        }

        return q.build();
    }

    // Constrains ?value to the node's class membership (e.g. ?value wdt:P31
    // wd:Q523 = "instance of star"), so a child edge keeps only the referenced
    // class's instances.
    private static void appendMembershipFilter(WikidataQueryBuilder q, RuleNode node) {
        if (node.hasMembershipFilter()) {
            q.rawWhere("?value wdt:" + node.membershipPid()
                    + " wd:" + node.membershipQid() + " .");
        }
    }

    // "Notable only": require an English Wikipedia article. A selective entry
    // that bounds a huge class (Q523 ~3M) to its ~2886 notable members, so the
    // scan completes and returns famous entities. See SPARQL rules R10.
    private static void appendSitelinkRequirement(WikidataQueryBuilder q, RuleNode node) {
        if (node.requireSitelink()) {
            q.rawWhere("?value_article schema:about ?value ; "
                    + "schema:isPartOf <https://en.wikipedia.org/> .");
        }
    }

    private static String labelLanguage(RuleNode node) {
        RuleLabelConfig cfg = node.labelConfig();
        String lang = cfg == null ? "en" : cfg.language();
        return lang == null || lang.isBlank() ? "en" : lang;
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
        appendValueFilterSelects(q, node, java.util.Set.of());
    }

    // skipFieldNames = value filters that reuse an included field's var (deduped,
    // R13) — their value is projected as the included field's var, not a second.
    private static void appendValueFilterSelects(
            WikidataQueryBuilder q, RuleNode node, java.util.Set<String> skipFieldNames) {
        for (wikidata.explore.filter.WikidataValueFilter vf : node.valueFilters()) {
            if (!WikidataValueFilterSparql.valid(vf)) continue;
            if (vf.fieldName() != null && skipFieldNames.contains(vf.fieldName())) continue;
            q.select(vf.variableName());
        }
    }

    // fieldName -> the included field's var, for fields that have BOTH a value
    // filter and an included field on the same property. The value filter binds
    // that one var (appendWhereOnVar) instead of a second binding that cross-
    // products (R13); the included field's own WHERE pattern is then skipped.
    private static java.util.Map<String, String> sharedFilterVars(
            RuleNode node, List<RuleIncludedField> skipFields, String varSuffix) {
        java.util.Set<String> filtered = new java.util.HashSet<>();
        for (wikidata.explore.filter.WikidataValueFilter vf : node.valueFilters())
            if (WikidataValueFilterSparql.valid(vf)
                    && vf.fieldName() != null && !vf.fieldName().isBlank())
                filtered.add(vf.fieldName());
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        if (filtered.isEmpty()) return map;
        int i = 0;
        for (RuleIncludedField f : node.includedFields()) {
            if (f != null && (skipFields == null || !skipFields.contains(f))
                    && f.fieldName() != null && filtered.contains(f.fieldName()))
                map.put(f.fieldName(),
                        RuleIncludedFieldSparql.variableName(f, i) + varSuffix);
            i++;
        }
        return map;
    }

    private static void appendIncludedFieldSelects(
            WikidataQueryBuilder q,
            RuleNode node,
            List<RuleIncludedField> skipFields) {
        appendIncludedFieldSelects(q, node, skipFields, true);
    }

    private static void appendIncludedFieldSelects(
            WikidataQueryBuilder q,
            RuleNode node,
            List<RuleIncludedField> skipFields,
            boolean withLabels) {

        if (node.includedFields().isEmpty()) return;
        int index = 0;
        for (RuleIncludedField field : node.includedFields()) {
            if (field == null) { index++; continue; }
            if (skipFields.contains(field)) { index++; continue; }

            String var = RuleIncludedFieldSparql.variableName(field, index);
            q.select(var);
            if (withLabels && !field.isMediaField()) q.select(var + "Label");
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
        appendValueFilterPatterns(q, node, java.util.Map.of());
    }

    // sharedVars: fieldName -> an already-bound included var to reuse (R13). A
    // matched filter binds that var; others emit their own binding + filter.
    private static void appendValueFilterPatterns(
            WikidataQueryBuilder q, RuleNode node,
            java.util.Map<String, String> sharedVars) {
        if (node.valueFilters().isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (wikidata.explore.filter.WikidataValueFilter vf : node.valueFilters()) {
            if (!WikidataValueFilterSparql.valid(vf)) continue;
            String shared = vf.fieldName() == null ? null : sharedVars.get(vf.fieldName());
            if (shared != null) {
                WikidataValueFilterSparql.appendWhereOnVar(sb, vf, shared);
            } else {
                WikidataValueFilterSparql.appendWherePatterns(sb, java.util.List.of(vf));
            }
        }
        if (!sb.isEmpty()) q.rawWhere(sb.toString());
    }

    private static void appendIncludedFieldPatterns(
            WikidataQueryBuilder q,
            RuleNode node,
            List<RuleIncludedField> skipFields) {
        appendIncludedFieldPatterns(q, node, skipFields, true);
    }

    private static void appendIncludedFieldPatterns(
            WikidataQueryBuilder q,
            RuleNode node,
            List<RuleIncludedField> skipFields,
            boolean withLabels) {

        if (node.includedFields().isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        List<RuleIncludedField> filtered =
                node.includedFields().stream()
                        .filter(f -> f != null && !skipFields.contains(f))
                        .toList();

        RuleIncludedFieldSparql.appendWherePatterns(sb, filtered, withLabels);
        if (!sb.isEmpty()) q.rawWhere(sb.toString());
    }
}
