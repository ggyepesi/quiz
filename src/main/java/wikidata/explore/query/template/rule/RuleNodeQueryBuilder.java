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

    /**
     * Stratified sample for a MULTI-target membership: one representative instance
     * per membership target (e.g. one nominee per Oscar category), via
     * {@code GROUP BY ?target / SAMPLE(?instance)}. A flat LIMIT could draw every
     * sample row from one target and miss the rest, so property discovery would
     * not see the class's full diversity (its subclass structure). Returns null
     * when there's only one (or no) target — the plain valuesQuery is fine then.
     */
    public static String stratifiedSampleQuery(RuleNode node) {
        if (node == null) {
            return null;
        }
        String pid = RuleNode.cleanPid(node.propertyPid());
        List<String> targets = new ArrayList<>();
        for (String t : node.allSourceQids()) {
            String q = RuleNode.cleanQid(t);
            if (q.matches("Q\\d+")) {
                targets.add(q);
            }
        }
        if (targets.size() < 2 || !pid.matches("P\\d+")) {
            return null;
        }
        // direction: ITEM_TO_ROOT -> ?inst wdt:pid ?target ; ROOT_TO_ITEM -> reverse
        String triple = node.direction().triplePattern("?target", "?inst", pid);
        String memberFilter = node.hasMembershipFilter()
                ? "      ?inst wdt:" + node.membershipPid()
                        + " wd:" + node.membershipQid() + " .\n"
                : "";
        // hint:optimizer "None" forces the written join order so the VALUES
        // binds ?target FIRST, then the predicate is looked up per bound target.
        // Without it the planner may scan every statement of a generic membership
        // predicate (e.g. P1411 "nominated for" — used by Grammy/Emmy/Nobel/…
        // across all of Wikidata) before filtering to these targets, which is the
        // worst-case that intermittently times the sample query out.
        return "SELECT ?value WHERE {\n"
                + "  { SELECT (SAMPLE(?inst) AS ?value) WHERE {\n"
                + "      hint:Query hint:optimizer \"None\" .\n"
                + "      " + wikidata.explore.query.template.sparql.SparqlValues
                        .clause("target", targets) + "\n"
                + "      " + triple + "\n"
                + memberFilter
                + "    } GROUP BY ?target }\n"
                + "}";
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

        // Two or more inlined (GROUP_CONCAT) fields stacked in ONE grouped subquery
        // cross-product each other per value (e.g. types × award targets), which is
        // the WDQS timeout. Split into a bounded %values base + one GROUP_CONCAT
        // subquery per field + an OPTIONAL-INCLUDE join, so N fields add
        // sum(rows) instead of product(rows). Zero/one inlined field has no cross-
        // product, so it keeps the single-subquery path below, byte-for-byte.
        if (inlinedFields.size() >= 2) {
            return splitInlinedValuesQuery(node, rootQidOrVar, inlinedFields);
        }

        String pid = RuleNode.cleanPid(node.propertyPid());
        boolean isVariable = rootQidOrVar != null
                && rootQidOrVar.startsWith("?");

        // For a BOUNDED multi-QID membership (a VALUES set — e.g. the Oscar
        // categories, ~11k nominees), the inline all-language VALUE label
        // (?value rdfs:label ?l . FILTER(LANG=en)) fetches every language's label
        // and times out; the set is already small, so label it via SERVICE over the
        // bounded rows in the outer instead. A BROAD single-type membership (e.g.
        // Q523 "star", 3M) keeps the inline label — there it RESTRICTS the scan to
        // named entities, which SERVICE can't. Field labels stay inline either way.
        boolean serviceValueLabel = !isVariable && !node.additionalSourceQids().isEmpty();

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

        // Optional, non-filtered display fields (e.g. a P18 image) are fetched in
        // the OUTER part of the named subquery — over the limited rows — not in
        // the inner scan. A single OPTIONAL P18 over Q523 added ~24s (R14). Only
        // for the non-inlined (potentially big-class) path; the inlined path
        // (small classes like constellations) is left unchanged.
        List<RuleIncludedField> outerFields = new ArrayList<>();
        if (inlinedFields.isEmpty()) {
            for (RuleIncludedField f : node.includedFields())
                if (f != null && f.optional()
                        && !sharedVars.containsKey(f.fieldName()))
                    outerFields.add(f);
        }
        List<RuleIncludedField> selectSkip = new ArrayList<>(inlinedFields);
        selectSkip.addAll(outerFields);
        List<RuleIncludedField> innerPatternSkip = new ArrayList<>(patternSkip);
        innerPatternSkip.addAll(outerFields);

        WikidataQueryBuilder q = new WikidataQueryBuilder();

        // The grouped path (GROUP_CONCAT inlining) GROUP BYs by every non-
        // aggregate select. A single-valued field whose property is actually
        // multi-valued (e.g. a film's many P577 release dates / P495 countries)
        // would then cross-product the rows, and the inner LIMIT — counting ROWS,
        // not distinct entities — gets exhausted by a handful of entities (98
        // Best-Picture films collapsed to ~4). SAMPLE the scalar fields so they
        // aggregate to one value per entity and GROUP BY reduces to ?value (R11/
        // R13-style; same fix childQueryForParent already applies to child edges).
        boolean grouped = !inlinedFields.isEmpty();

        q.select("value");
        if (!useService && !serviceValueLabel) q.select("valueLabel");
        appendValueFilterSelects(q, node, sharedVars.keySet());
        if (grouped) {
            appendGroupedScalarSelects(q, node, selectSkip, !useService);
        } else {
            appendIncludedFieldSelects(q, node, selectSkip, !useService);
        }

        for (RuleIncludedField field : inlinedFields) {
            q.groupConcat(
                    inlinedPairVar(field),
                    inlinedFieldAlias(field),
                    "|");
        }

        // A child edge generated for a SPECIFIC parent is bound by the EDGE
        // (?value <pid> wd:<parent>), so it has a real constraint even with no
        // type membership of its own (e.g. a category's nominees, reached via
        // incoming P1411 to a relational class). Treating that as the empty case
        // is what dropped such children entirely.
        boolean parentAnchored = !isVariable
                && RuleNode.cleanQid(rootQidOrVar).matches("Q\\d+");
        boolean blankMembership = !isVariable
                && node.additionalSourceQids().isEmpty()
                && RuleNode.cleanQid(node.sourceQid()).isBlank()
                && !parentAnchored;

        // No membership AND no seed QIDs: there's nothing to populate the class
        // from. Without a real constraint, ?value is unbound and the label
        // pattern below matches EVERY labelled entity — a full-database scan that
        // returns arbitrary junk (Q27168368 …). Bind ?value to the empty set so
        // the result is genuinely empty (and cheap), as the class definition
        // implies. (A class meant to be materialised by a Transform — e.g. an
        // Oscar/Nomination event — has no Wikidata membership and should not be
        // generated directly; it'll come from the Transform, not this query.)
        boolean emptyResult = blankMembership && node.includedQids().isEmpty();

        if (blankMembership) {
            // No class membership: the instances ARE the seed QIDs, bound by the
            // VALUES ?value {…} that appendAllowedQids emits below. Emit no
            // membership triple (which would be a broken "wd: ?value …").
            if (node.includedQids().isEmpty()) {
                q.rawWhere("# no membership and no seed QIDs — empty result\n"
                        + "  VALUES ?value { }");
            }
        } else if (!isVariable && node.additionalSourceQids().isEmpty()) {
            // Single QID: emit the constant directly (?value wdt:P31 wd:Qxxx),
            // NOT BIND(wd:Qxxx AS ?root) + ?value wdt:P31 ?root. The BIND makes
            // the planner miss the constant/sitelink as the entry — ~18s vs ~1s
            // for the notable-star root (R15).
            q.rawWhere(node.direction().triplePattern(
                    "wd:" + RuleNode.cleanQid(rootQidOrVar), "?value", pid));
        } else {
            if (!isVariable) {
                // Multi-QID membership: instance-of ANY of the listed types.
                q.valuesQids("root", node.allSourceQids());
            } else {
                q.rawWhere("# template: ?root supplied by VALUES clause at runtime");
            }
            q.rawWhere(node.direction().triplePattern("?root", "?value", pid));
        }
        appendMembershipFilter(q, node);
        appendSitelinkRequirement(q, node);

        appendAllowedQids(q, node);
        appendExcludedQids(q, node);
        appendPredicateObjectExclusions(q, node);
        appendValueFilterPatterns(q, node, sharedVars);
        if (grouped) {
            appendGroupedScalarPatterns(q, node, innerPatternSkip, !useService);
        } else {
            appendIncludedFieldPatterns(q, node, innerPatternSkip, !useService);
        }
        // ?root is a bound variable only in the multi-QID / template branch
        // above (not the single-constant case, which emits wd:Qxxx directly).
        boolean rootBound = !blankMembership
                && (isVariable || !node.additionalSourceQids().isEmpty());
        appendInlinedFieldPatterns(q, inlinedFields, node, rootBound);
        // Skip the label pattern for the empty case — with ?value bound to the
        // empty set it would add nothing, but emitting it invites a full label
        // scan if a planner ignores the empty VALUES.
        if (!useService && !serviceValueLabel && !emptyResult) appendLabelPattern(q, node);

        if (node.hasRank()) {
            // Class-level importance ranking: keep the top `limit` by a measure.
            // ORDER BY goes INSIDE the limited subquery (R11) so the LIMIT picks
            // the top-N, not an arbitrary N. Group so a multi-valued measure
            // aggregates to one row per entity.
            if (node.rankBySitelinks()) {
                q.rawWhere("?value wikibase:sitelinks ?rankMeasure .");
            } else {
                q.rawWhere("OPTIONAL { ?value wdt:"
                        + RuleNode.cleanPid(node.rankPropertyPid())
                        + " ?rankMeasure . }");
            }
            q.groupByNonAggregateSelects();
            q.orderByRaw((node.rankDescending() ? "DESC" : "ASC")
                    + "(MAX(?rankMeasure))");
            q.limit(node.limit());
        } else if (inlinedFields.isEmpty()) {
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

        // OPTIONAL display patterns for the outer part (full-list index kept).
        StringBuilder outerSb = new StringBuilder();
        if (!outerFields.isEmpty()) {
            List<RuleIncludedField> outerSkip =
                    new ArrayList<>(node.includedFields());
            outerSkip.removeAll(outerFields);
            RuleIncludedFieldSparql.appendWherePatterns(
                    outerSb, node.includedFields(), !useService, outerSkip);
        }

        String prefix = isVariable
                ? "# NOTE: query template — ?root replaced by VALUES at runtime.\n\n"
                : "";
        // When the value label is SERVICE-labelled, bind it over the bounded outer
        // rows (not inline in the inner scan).
        String outer = outerSb.toString()
                + (serviceValueLabel ? labelService(labelLanguage(node)) : "");
        return prefix + namedSubquerySort(q.build(), outer, "valueLabel");
    }

    /**
     * Multi-inlined-field query, de-cartesianed: a bounded {@code %values} base
     * (value + label + non-inlined scalars, grouped so a multi-valued scalar is
     * SAMPLEd not cross-producted — R11) then ONE {@code GROUP_CONCAT} subquery per
     * inlined field over that base, joined with {@code INCLUDE %values} +
     * {@code OPTIONAL { INCLUDE %field }}. N inlined fields add sum(rows) instead of
     * product(rows) — the fix for the type×target timeout. The non-OPTIONAL pattern
     * inside each subquery + OPTIONAL INCLUDE outside means a value lacking a
     * type/target keeps its row (unbound concat), and no value is dropped.
     */
    private static String splitInlinedValuesQuery(
            RuleNode node, String rootQidOrVar, List<RuleIncludedField> inlinedFields) {

        boolean isVariable = rootQidOrVar != null && rootQidOrVar.startsWith("?");
        String pid = RuleNode.cleanPid(node.propertyPid());

        // Same membership/filter/label emission as the single path, minus the
        // inlined concats/patterns (each inlined field gets its own subquery).
        java.util.Map<String, String> sharedVars = sharedFilterVars(node, inlinedFields, "");
        List<RuleIncludedField> selectSkip = new ArrayList<>(inlinedFields);
        List<RuleIncludedField> patternSkip = new ArrayList<>(inlinedFields);
        for (RuleIncludedField f : node.includedFields()) {
            if (f != null && sharedVars.containsKey(f.fieldName())) {
                patternSkip.add(f);
            }
        }

        boolean parentAnchored = !isVariable
                && RuleNode.cleanQid(rootQidOrVar).matches("Q\\d+");
        boolean blankMembership = !isVariable
                && node.additionalSourceQids().isEmpty()
                && RuleNode.cleanQid(node.sourceQid()).isBlank()
                && !parentAnchored;
        boolean emptyResult = blankMembership && node.includedQids().isEmpty();

        // %categories reuse: a fixed multi-QID membership set, hoisted into a named
        // subquery, is reused by any inlined field that walks the SAME
        // predicate/direction — because that field's object values ARE the
        // membership set. E.g. Oscars `target` = the categories a value was
        // nominated for = the roots, so it joins the known ~59-QID set instead of a
        // self-join + `?t wdt:P31 <awardType>` re-filter per target.
        boolean multiQid = !isVariable && !blankMembership
                && !node.additionalSourceQids().isEmpty();
        String memberTriple = node.direction().triplePattern("?root", "?value", pid);
        List<RuleIncludedField> reuseFields = new ArrayList<>();
        if (multiQid) {
            for (RuleIncludedField f : inlinedFields) {
                if (reusesMembership(f, memberTriple)) {
                    reuseFields.add(f);
                }
            }
        }
        boolean useCategories = !reuseFields.isEmpty();

        WikidataQueryBuilder base = new WikidataQueryBuilder();
        base.distinct(false);   // GROUP BY (below) is the deduplicator, not DISTINCT
        base.select("value");
        appendValueFilterSelects(base, node, sharedVars.keySet());
        appendGroupedScalarSelects(base, node, selectSkip, true);

        if (blankMembership) {
            if (node.includedQids().isEmpty()) {
                base.rawWhere("# no membership and no seed QIDs — empty result\n"
                        + "  VALUES ?value { }");
            }
        } else if (!isVariable && node.additionalSourceQids().isEmpty()) {
            base.rawWhere(node.direction().triplePattern(
                    "wd:" + RuleNode.cleanQid(rootQidOrVar), "?value", pid));
        } else if (useCategories) {
            base.rawWhere("INCLUDE %categories .");
            base.rawWhere(node.direction().triplePattern("?category", "?value", pid));
        } else {
            if (!isVariable) {
                base.valuesQids("root", node.allSourceQids());
            } else {
                base.rawWhere("# template: ?root supplied by VALUES clause at runtime");
            }
            base.rawWhere(node.direction().triplePattern("?root", "?value", pid));
        }
        appendMembershipFilter(base, node);
        appendSitelinkRequirement(base, node);
        appendAllowedQids(base, node);
        appendExcludedQids(base, node);
        appendPredicateObjectExclusions(base, node);
        appendValueFilterPatterns(base, node, sharedVars);
        appendGroupedScalarPatterns(base, node, patternSkip, true);
        // NB: no inline rdfs:label here — bound ?value first (by membership), then
        // label the bounded set via SERVICE in the outer. An inline
        // `?value rdfs:label ?l . FILTER(LANG=en)` fetches ALL-language labels for
        // every candidate and times out on a big relational membership (P1411
        // nominees ~11k: >30s → partial results → too few roots); the SERVICE labels
        // only the matched rows in the requested language (~9s, complete).
        base.groupByNonAggregateSelects();
        base.limit(node.limit());

        StringBuilder sb = new StringBuilder();
        if (isVariable) {
            sb.append("# NOTE: query template — ?root replaced by VALUES at runtime.\n\n");
        }
        sb.append("SELECT *\n");
        if (useCategories) {
            WikidataQueryBuilder cats = new WikidataQueryBuilder();
            cats.distinct(false);
            cats.select("category");
            cats.valuesQids("category", node.allSourceQids());
            sb.append("WITH {\n").append(cats.build().indent(2)).append("} AS %categories\n");
        }
        sb.append("WITH {\n")
                .append(base.build().indent(2))
                .append("} AS %values\n");

        List<String> subNames = new ArrayList<>();
        for (RuleIncludedField field : inlinedFields) {
            String subName = "%" + inlinedFieldAlias(field);   // e.g. %type_inlined
            subNames.add(subName);
            sb.append("WITH {\n")
                    .append(fieldConcatSubquery(node, field, reuseFields.contains(field))
                            .indent(2))
                    .append("} AS ").append(subName).append("\n");
        }

        sb.append("WHERE {\n  INCLUDE %values .\n");
        for (String subName : subNames) {
            sb.append("  OPTIONAL { INCLUDE ").append(subName).append(" . }\n");
        }
        // Label the bounded ?value set here (not inline in the base) — cheap over
        // the ≤ limit rows, and it can't force a full-class label scan.
        sb.append(labelService(labelLanguage(node)));
        // No outer ORDER BY: the LIMIT already lives inside %values, so ordering
        // here is purely cosmetic — but ORDER BY ?valueLabel forces WDQS to resolve
        // ALL of the (≤ limit, ~11k for P1411 nominees) SERVICE labels and globally
        // sort before emitting a row, which tips the query over the timeout. The
        // pool is keyed by QID and the app sorts/canonicalizes later, so stream it
        // unordered instead.
        sb.append("}\n");
        return sb.toString();
    }

    // One inlined field's GROUP_CONCAT over the bounded %values base.
    //   reuseCategories: the field walks the same predicate/direction as membership,
    //     so its values ARE the %categories set — reuse it (join the known set)
    //     instead of a self-join + type re-filter per value.
    //   else: ?root isn't exported by %values, so the field walks its own predicate
    //     from ?value; the INCLUDE keeps it bounded to the base values.
    private static String fieldConcatSubquery(
            RuleNode node, RuleIncludedField field, boolean reuseCategories) {
        WikidataQueryBuilder q = new WikidataQueryBuilder();
        q.select("value");
        q.groupConcat(inlinedPairVar(field), inlinedFieldAlias(field), "|");
        q.rawWhere("INCLUDE %values .");
        if (reuseCategories) {
            String pid = RuleNode.cleanPid(field.propertyPid());
            String labelVar = inlinedLabelVar(field);
            String pairVar = inlinedPairVar(field);
            q.rawWhere("INCLUDE %categories .");
            q.rawWhere(field.direction().triplePattern("?value", "?category", pid));
            q.rawWhere("""
                OPTIONAL {
                  ?category rdfs:label %s .
                  FILTER(LANG(%s) = "en")
                }
                BIND(CONCAT(STR(?category), "%s", COALESCE(%s, "")) AS ?%s)
                """.formatted(labelVar, labelVar, PAIR_SEPARATOR, labelVar, pairVar));
        } else {
            appendInlinedFieldPatterns(q, List.of(field), node, false);
        }
        q.groupByNonAggregateSelects();
        return q.build();
    }

    // A single-ENTITY inlined field whose predicate/direction matches the class
    // membership: its object values ARE the membership set, so it can reuse the
    // hoisted %categories subquery instead of self-joining the predicate + a type
    // re-filter per value. The field's own membership (e.g. target's P31=Q19020) is
    // SUBSUMED by %categories — the roots are exactly that type — so it's dropped.
    // (Mirrors the reuseRoot check in appendInlinedFieldPatterns, but keyed off the
    // named set, not a bound ?root.)
    private static boolean reusesMembership(RuleIncludedField field, String memberTriple) {
        if (field == null) {
            return false;
        }
        String pid = RuleNode.cleanPid(field.propertyPid());
        return memberTriple.equals(
                field.direction().triplePattern("?value", "?root", pid));
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
        // The en,mul fallback lives in one place now — wikidata.query.LabelService.
        return wikidata.query.LabelService.service(lang, "value", "valueLabel");
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

        return namedSubquerySort(innerQuery, "", orderVar);
    }

    public static String namedSubquerySort(String innerQuery, String orderVar) {
        return namedSubquerySort(innerQuery, "", orderVar);
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
            String outerPatterns,
            String orderVar) {

        String outer = outerPatterns == null ? "" : outerPatterns;
        return "SELECT *\n"
                + "WITH {\n"
                + innerQuery.indent(2)
                + "} AS %limited\n"
                + "WHERE {\n  INCLUDE %limited .\n"
                + outer
                + "}\n"
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
            List<RuleIncludedField> inlinedFields,
            RuleNode node,
            boolean rootBound) {

        // The class membership traverses this predicate/direction from ?value.
        String memberTriple = node.direction().triplePattern(
                "?root", "?value", RuleNode.cleanPid(node.propertyPid()));

        for (RuleIncludedField field : inlinedFields) {
            String pid = RuleNode.cleanPid(field.propertyPid());
            String valueVar = inlinedValueVar(field);
            String labelVar = inlinedLabelVar(field);
            String pairVar = inlinedPairVar(field);

            // If this inlined field walks the SAME predicate/direction from ?value
            // as the membership, its values ARE the matched ?root(s): reuse the
            // already-bound ?root instead of a second self-join over that predicate.
            // The self-join cross-products (N roots x M targets) per value AND
            // re-fetches values outside the root set that get restricted back to it
            // anyway — reuse both restores correctness and is far cheaper (Oscars
            // P1411 target: ~62s -> ~10s). Same predicate + direction => the field's
            // object variable is interchangeable with ?root.
            boolean reuseRoot = rootBound
                    && !field.hasMembership()
                    && memberTriple.equals(
                            field.direction().triplePattern("?value", "?root", pid));
            if (reuseRoot) {
                q.rawWhere("""
                    OPTIONAL {
                      ?root rdfs:label %s .
                      FILTER(LANG(%s) = "en")
                    }
                    BIND(CONCAT(STR(?root), "§", COALESCE(%s, "")) AS ?%s)
                    """.formatted(labelVar, labelVar, labelVar, pairVar));
                continue;
            }

            // ROOT_TO_ITEM: ?value wdt:P ?x_gc ; ITEM_TO_ROOT: ?x_gc wdt:P ?value
            // (incoming, e.g. "episode in"/"facet of" pointing AT this entity).
            String triple = field.direction().triplePattern("?value", valueVar, pid);
            // Constrain the value to the referenced class's type, if requested
            // (e.g. keep only P31=Episode, dropping videogames/novels).
            String typeConstraint = field.hasMembership()
                    ? "  " + valueVar + " wdt:" + field.membershipPid()
                            + " wd:" + field.membershipQid() + " .\n"
                    : "";

            q.rawWhere("""
                OPTIONAL {
                  %s
                %s  OPTIONAL {
                    %s rdfs:label %s .
                    FILTER(LANG(%s) = "en")
                  }
                  BIND(CONCAT(STR(%s), "§", COALESCE(%s, "")) AS ?%s)
                }
                """.formatted(
                    triple,
                    typeConstraint,
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

    // Grouped-path selects: SAMPLE each single-valued field (so it aggregates to
    // one value per entity and drops out of GROUP BY); a non-inlined COLLECTION
    // field stays a plain non-aggregate select (its multiplicity is intended).
    // The SAMPLE source is ?<var>_s, projected back AS ?<var> so the SELECT name
    // and the extractor's index-based var are unchanged.
    private static void appendGroupedScalarSelects(
            WikidataQueryBuilder q, RuleNode node,
            List<RuleIncludedField> skipFields, boolean withLabels) {

        if (node.includedFields().isEmpty()) return;
        int index = 0;
        for (RuleIncludedField field : node.includedFields()) {
            if (field == null) { index++; continue; }
            if (skipFields.contains(field)) { index++; continue; }
            String var = RuleIncludedFieldSparql.variableName(field, index);
            if (field.collection()) {
                q.select(var);
                if (withLabels && !field.isMediaField()) q.select(var + "Label");
            } else {
                q.selectRaw("(SAMPLE(?" + var + "_s) AS ?" + var + ")");
                if (withLabels && !field.isMediaField()) {
                    q.selectRaw("(SAMPLE(?" + var + "_sLabel) AS ?" + var + "Label)");
                }
            }
            index++;
        }
    }

    // Grouped-path patterns matching {@link #appendGroupedScalarSelects}: scalar
    // fields bind ?<var>_s (SAMPLE source); collection fields keep the shared
    // util's plain binding. Honours direction, optionality, a membership type
    // constraint, and media (no label).
    private static void appendGroupedScalarPatterns(
            WikidataQueryBuilder q, RuleNode node,
            List<RuleIncludedField> skipFields, boolean withLabels) {

        if (node.includedFields().isEmpty()) return;

        // Collection (non-inlined) fields: delegate to the shared util, skipping
        // every scalar so only collections are emitted with plain vars.
        List<RuleIncludedField> scalarSkip = new ArrayList<>(skipFields);
        for (RuleIncludedField f : node.includedFields()) {
            if (f != null && !f.collection()) scalarSkip.add(f);
        }
        StringBuilder collSb = new StringBuilder();
        RuleIncludedFieldSparql.appendWherePatterns(
                collSb, node.includedFields(), withLabels, scalarSkip);
        if (!collSb.isEmpty()) q.rawWhere(collSb.toString());

        // Scalar fields: emit with the ?<var>_s SAMPLE source var.
        int index = 0;
        for (RuleIncludedField field : node.includedFields()) {
            if (field == null
                    || field.propertyPid() == null || field.propertyPid().isBlank()
                    || field.collection() || skipFields.contains(field)) {
                index++;
                continue;
            }
            String var = RuleIncludedFieldSparql.variableName(field, index) + "_s";
            boolean label = withLabels && !field.isMediaField();
            String triple = field.direction()
                    .triplePattern("?value", "?" + var, field.propertyPid());
            StringBuilder sb = new StringBuilder();
            sb.append("  OPTIONAL {\n    ").append(triple).append("\n");
            if (field.hasMembership()) {
                sb.append("    ?").append(var).append(" wdt:")
                  .append(field.membershipPid()).append(" wd:")
                  .append(field.membershipQid()).append(" .\n");
            }
            if (label) {
                sb.append("    OPTIONAL { ?").append(var)
                  .append(" rdfs:label ?").append(var)
                  .append("Label . FILTER(LANG(?").append(var)
                  .append("Label) = \"en\") }\n");
            }
            sb.append("  }\n");
            q.rawWhere(sb.toString());
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

        // Pass the FULL field list + the skip set (not a pre-filtered list) so
        // the pattern var index matches the SELECT/extractor, which index over
        // the full list with skip-and-increment. Pre-filtering would re-index
        // later fields (e.g. image -> ?image_0 in the pattern but ?image_1 in
        // the SELECT, so the image never binds).
        StringBuilder sb = new StringBuilder();
        RuleIncludedFieldSparql.appendWherePatterns(
                sb, node.includedFields(), withLabels, skipFields);
        if (!sb.isEmpty()) q.rawWhere(sb.toString());
    }
}
