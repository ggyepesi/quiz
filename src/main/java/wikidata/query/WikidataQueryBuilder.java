package wikidata.query;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Small pragmatic SPARQL builder for Wikidata queries.
 *
 * Changes from previous version:
 *   groupConcat(var, asVar, separator)  — GROUP_CONCAT projection for inlined edges
 *   rdfsLabelPattern(var, lang, req)    — encapsulates the rdfs:label + FILTER pattern
 *   filterInAsValues(var, qids)         — VALUES-based allowlist (index join, faster than FILTER IN)
 *   pidList(pids)                       — parallel to wdList for property PIDs
 */
public class WikidataQueryBuilder {

    private final Set<String>   select   = new LinkedHashSet<>();
    private final StringBuilder where    = new StringBuilder();
    private final Set<String>   labelVars = new LinkedHashSet<>();
    private final Set<String>   groupBy  = new LinkedHashSet<>();
    private final Set<String>   orderBy  = new LinkedHashSet<>();
    private final StringBuilder having   = new StringBuilder();

    private boolean distinct = true;
    private int limit  = -1;
    private int offset = -1;

    // ------------------------------------------------------------------
    // Static factory queries (unchanged)
    // ------------------------------------------------------------------

    public static String entitySearch(String text, int limit) {
        String escaped = sparqlString(text);
        return """
                SELECT DISTINCT ?item ?itemLabel ?itemDescription WHERE {
                  SERVICE wikibase:mwapi {
                    bd:serviceParam wikibase:endpoint "www.wikidata.org" .
                    bd:serviceParam wikibase:api "EntitySearch" .
                    bd:serviceParam mwapi:search "%s" .
                    bd:serviceParam mwapi:language "en" .
                    ?item wikibase:apiOutputItem mwapi:item .
                  }
                  SERVICE wikibase:label {
                    bd:serviceParam wikibase:language "en" .
                    ?item rdfs:label ?itemLabel .
                    ?item schema:description ?itemDescription .
                  }
                }
                LIMIT %d
                """.formatted(escaped, Math.max(1, limit));
    }

    public static String exactLabelOrAlias(String text, int limit) {
        String escaped = sparqlString(text);
        return """
                SELECT DISTINCT ?item ?itemLabel ?itemDescription WHERE {
                  { ?item rdfs:label "%s"@en . }
                  UNION
                  { ?item skos:altLabel "%s"@en . }
                  SERVICE wikibase:label {
                    bd:serviceParam wikibase:language "en" .
                    ?item rdfs:label ?itemLabel .
                    ?item schema:description ?itemDescription .
                  }
                }
                LIMIT %d
                """.formatted(escaped, escaped, Math.max(1, limit));
    }

    /**
     * Discovers outgoing and incoming properties for a sample of items in a single UNION query.
     * Each result row has a ?direction binding ("outgoing" or "incoming").
     */
    /**
     * Discovers outgoing and incoming properties for a sample of items in a single query.
     *
     * Each direction is a fully independent subquery (GROUP BY + LIMIT inside the subquery)
     * so Blazegraph optimizes each branch separately — same performance as two individual
     * queries. The outer UNION just concatenates the two result sets.
     */
    public static String discoverProperties(Collection<String> qids, int limit) {
        String values = wdList(qids);
        int n = Math.max(1, limit);
        return """
                SELECT ?prop ?propLabel ?type ?direction ?count ?example ?exampleLabel
                WHERE {
                  {
                    SELECT ?prop ?propLabel ?type ("outgoing" AS ?direction)
                      (COUNT(DISTINCT ?item) AS ?count)
                      (SAMPLE(?v) AS ?example)
                      (SAMPLE(?vLabel) AS ?exampleLabel)
                    WHERE {
                      VALUES ?item { %s }
                      ?item ?propUri ?v .
                      ?prop wikibase:directClaim ?propUri .
                      OPTIONAL { ?prop wikibase:propertyType ?type . }
                      OPTIONAL {
                        ?v rdfs:label ?vLabel .
                        FILTER(LANG(?vLabel) = "en")
                      }
                      SERVICE wikibase:label { bd:serviceParam wikibase:language "en" . }
                    }
                    GROUP BY ?prop ?propLabel ?type
                    ORDER BY DESC(?count)
                    LIMIT %d
                  }
                  UNION
                  {
                    SELECT ?prop ?propLabel ?type ("incoming" AS ?direction)
                      (COUNT(DISTINCT ?item) AS ?count)
                      (SAMPLE(?subject) AS ?example)
                      (SAMPLE(?subjectLabel) AS ?exampleLabel)
                    WHERE {
                      VALUES ?item { %s }
                      ?subject ?propUri ?item .
                      FILTER(STRSTARTS(STR(?subject), "http://www.wikidata.org/entity/"))
                      ?prop wikibase:directClaim ?propUri .
                      OPTIONAL { ?prop wikibase:propertyType ?type . }
                      OPTIONAL {
                        ?subject rdfs:label ?subjectLabel .
                        FILTER(LANG(?subjectLabel) = "en")
                      }
                      SERVICE wikibase:label { bd:serviceParam wikibase:language "en" . }
                    }
                    GROUP BY ?prop ?propLabel ?type
                    ORDER BY DESC(?count)
                    LIMIT %d
                  }
                }
                ORDER BY ?direction DESC(?count)
                """.formatted(values, n, values, n);
    }

    public static String outgoingDirectStatements(String qid, int limit) {
        qid = cleanQid(qid);
        return """
                SELECT DISTINCT ?property ?value ?valueLabel WHERE {
                  wd:%s ?p ?value .
                  FILTER(STRSTARTS(STR(?p), "http://www.wikidata.org/prop/direct/"))
                  ?property wikibase:directClaim ?p .
                  OPTIONAL {
                    ?value rdfs:label ?valueLabel .
                    FILTER(LANG(?valueLabel) = "en")
                  }
                }
                ORDER BY ?property ?valueLabel
                LIMIT %d
                """.formatted(qid, Math.max(1, limit));
    }

    // ------------------------------------------------------------------
    // Static utilities
    // ------------------------------------------------------------------

    public static String cleanQid(String qid) {
        if (qid == null) return "";
        qid = qid.trim();
        if (qid.startsWith("wd:")) qid = qid.substring(3);
        return qid.trim();
    }

    public static String cleanPid(String pid) {
        if (pid == null) return "";
        pid = pid.trim();
        if (pid.startsWith("wdt:")) pid = pid.substring(4);
        if (pid.startsWith("wd:"))  pid = pid.substring(3);
        return pid.trim();
    }

    public static String sparqlString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Builds a space-separated "wd:Qxxx" list from QID strings.
     * Entries that don't match Q\d+ are silently skipped.
     */
    public static String wdList(Collection<String> qids) {
        if (qids == null || qids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String qid : qids) {
            qid = cleanQid(qid);
            if (!qid.matches("Q\\d+")) continue;
            if (!sb.isEmpty()) sb.append(" ");
            sb.append("wd:").append(qid);
        }
        return sb.toString();
    }

    /**
     * Builds a space-separated "wdt:Pxxx" list from PID strings.
     * Entries that don't match P\d+ are silently skipped.
     */
    public static String pidList(Collection<String> pids) {
        if (pids == null || pids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String pid : pids) {
            pid = cleanPid(pid);
            if (!pid.matches("P\\d+")) continue;
            if (!sb.isEmpty()) sb.append(" ");
            sb.append("wdt:").append(pid);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // SELECT
    // ------------------------------------------------------------------

    public WikidataQueryBuilder distinct(boolean distinct) {
        this.distinct = distinct; return this;
    }

    public WikidataQueryBuilder select(String... vars) {
        for (String v : vars) select.add(var(v)); return this;
    }

    public WikidataQueryBuilder selectRaw(String expression) {
        if (expression != null && !expression.isBlank()) select.add(expression.trim());
        return this;
    }

    public WikidataQueryBuilder selectDistinct(String... vars) {
        distinct = true; select.clear();
        for (String v : vars) select.add(var(v)); return this;
    }

    public WikidataQueryBuilder selectEntity(String v) {
        select(v, v + "Label"); label(v); return this;
    }

    public WikidataQueryBuilder countDistinct(String var, String asVar) {
        distinct(false);
        selectRaw("(COUNT(DISTINCT " + var(var) + ") AS " + var(asVar) + ")");
        return this;
    }

    public WikidataQueryBuilder countAll(String asVar) {
        distinct(false);
        selectRaw("(COUNT(*) AS " + var(asVar) + ")");
        return this;
    }

    /**
     * Adds a GROUP_CONCAT projection to SELECT.
     *
     * Emits: (GROUP_CONCAT(DISTINCT ?var; SEPARATOR="sep") AS ?asVar)
     *
     * Automatically switches off DISTINCT — GROUP_CONCAT and SELECT DISTINCT
     * are incompatible in SPARQL; GROUP BY handles deduplication instead.
     *
     * Used by inlinedValuesQuery to fold simple child edges into the root query.
     */
    public WikidataQueryBuilder groupConcat(
            String var, String asVar, String separator) {
        distinct(false);
        String sep = separator == null ? "|" : sparqlString(separator);
        selectRaw("(GROUP_CONCAT(DISTINCT " + var(var)
                + "; SEPARATOR=\"" + sep + "\") AS " + var(asVar) + ")");
        return this;
    }

    // ------------------------------------------------------------------
    // WHERE patterns
    // ------------------------------------------------------------------

    public WikidataQueryBuilder bind(String expression, String var) {
        where.append("  BIND(").append(expression)
             .append(" AS ").append(var(var)).append(")\n");
        return this;
    }

    public WikidataQueryBuilder bindEntity(String var, String qid) {
        return bind("wd:" + cleanQid(qid), var);
    }

    public WikidataQueryBuilder bindBoolean(String var, boolean value) {
        bind(String.valueOf(value), var); select(var); return this;
    }

    public WikidataQueryBuilder triple(
            String subject, String predicate, String object) {
        where.append("  ").append(term(subject)).append(" ")
             .append(predicate).append(" ").append(term(object)).append(" .\n");
        return this;
    }

    public WikidataQueryBuilder statement(
            String subjectVar, String property,
            String statementVar, String valueVar) {
        property = cleanPid(property);
        return triple(subjectVar, "p:" + property, statementVar)
               .triple(statementVar, "ps:" + property, valueVar);
    }

    public WikidataQueryBuilder truthy(
            String subjectVar, String property, String valueVar) {
        return triple(subjectVar, "wdt:" + cleanPid(property), valueVar);
    }

    public WikidataQueryBuilder qualifier(
            String statementVar, String property, String valueVar) {
        return triple(statementVar, "pq:" + cleanPid(property), valueVar);
    }

    public WikidataQueryBuilder optional(String body) {
        if (body == null || body.isBlank()) return this;
        where.append("  OPTIONAL {\n");
        appendIndented(where, body, "    ");
        where.append("  }\n");
        return this;
    }

    public WikidataQueryBuilder optionalTriple(
            String subject, String predicate, String object) {
        where.append("  OPTIONAL { ").append(term(subject)).append(" ")
             .append(predicate).append(" ").append(term(object))
             .append(" . }\n");
        return this;
    }

    public WikidataQueryBuilder optionalQualifier(
            String statementVar, String property, String valueVar) {
        optionalTriple(statementVar, "pq:" + cleanPid(property), valueVar);
        select(valueVar); return this;
    }

    public WikidataQueryBuilder optionalTruthy(
            String subjectVar, String property, String valueVar) {
        optionalTriple(subjectVar, "wdt:" + cleanPid(property), valueVar);
        select(valueVar); return this;
    }

    public WikidataQueryBuilder exists(String var, String body) {
        where.append("  BIND(EXISTS {\n");
        appendIndented(where, body, "    ");
        where.append("  } AS ").append(var(var)).append(")\n");
        select(var); return this;
    }

    // ------------------------------------------------------------------
    // Label patterns
    // ------------------------------------------------------------------

    public WikidataQueryBuilder label(String... vars) {
        for (String v : vars) labelVars.add(var(v)); return this;
    }

    public WikidataQueryBuilder serviceLabel(String language) {
        where("""
              SERVICE wikibase:label {
                bd:serviceParam wikibase:language "%s".
              }
              """.formatted(language == null || language.isBlank()
                      ? "en" : language));
        return this;
    }

    /**
     * Emits an explicit rdfs:label pattern — faster than the wikibase label
     * SERVICE for large result sets (>50 rows).
     *
     * When required=true:
     *   ?entityVar rdfs:label ?entityVarLabel .
     *   FILTER(LANG(?entityVarLabel) = "lang")   ← omitted when lang is "any"
     *
     * When required=false:
     *   OPTIONAL {
     *     ?entityVar rdfs:label ?entityVarLabel .
     *     FILTER(LANG(?entityVarLabel) = "lang")  ← omitted when lang is "any"
     *   }
     *
     * The label variable is always entityVar + "Label".
     * This replaces the hand-rolled triple+filter pattern in RuleTreeQueries.appendLabelPattern.
     */
    public WikidataQueryBuilder rdfsLabelPattern(
            String entityVar, String lang, boolean required) {

        String labelVar = entityVar + "Label";
        boolean anyLang = lang == null || lang.isBlank()
                || "any".equalsIgnoreCase(lang);

        String triple = "?" + entityVar + " rdfs:label ?" + labelVar + " .";
        String langFilter = anyLang
                ? null
                : "FILTER(LANG(?" + labelVar + ") = \""
                  + sparqlString(lang) + "\")";

        if (required) {
            where.append("  ").append(triple).append("\n");
            if (langFilter != null)
                where.append("  ").append(langFilter).append("\n");
        } else {
            where.append("  OPTIONAL {\n");
            where.append("    ").append(triple).append("\n");
            if (langFilter != null)
                where.append("    ").append(langFilter).append("\n");
            where.append("  }\n");
        }

        return this;
    }

    // ------------------------------------------------------------------
    // FILTER
    // ------------------------------------------------------------------

    public WikidataQueryBuilder filter(String expression) {
        if (expression != null && !expression.isBlank())
            where.append("  FILTER(").append(expression.trim()).append(")\n");
        return this;
    }

    public WikidataQueryBuilder filterLang(String var, String lang) {
        if (lang == null || lang.isBlank() || "any".equalsIgnoreCase(lang))
            return this;
        return filter("LANG(" + var(var) + ") = \"" + sparqlString(lang) + "\"");
    }

    public WikidataQueryBuilder filterInQids(
            String var, Collection<String> qids) {
        String values = wdList(qids);
        if (values.isBlank()) return this;
        return filter(var(var) + " IN (" + values.replace(" ", ", ") + ")");
    }

    public WikidataQueryBuilder filterNotInQids(
            String var, Collection<String> qids) {
        String values = wdList(qids);
        if (values.isBlank()) return this;
        return filter(var(var) + " NOT IN (" + values.replace(" ", ", ") + ")");
    }

    public WikidataQueryBuilder filterNotExists(String body) {
        if (body == null || body.isBlank()) return this;
        where.append("  FILTER NOT EXISTS {\n");
        appendIndented(where, body, "    ");
        where.append("  }\n");
        return this;
    }

    /**
     * Allowlist using a VALUES clause instead of FILTER IN.
     *
     * VALUES lets the SPARQL engine use an index join, which is significantly
     * faster than FILTER IN for large QID lists. Semantically identical —
     * only matching ?var values pass through.
     *
     * Use this instead of filterInQids for the includedQids allowlist.
     */
    public WikidataQueryBuilder filterInAsValues(
            String var, Collection<String> qids) {
        // VALUES is semantically equivalent to FILTER IN but uses an index join
        return valuesQids(var, qids);
    }

    // ------------------------------------------------------------------
    // VALUES
    // ------------------------------------------------------------------

    public WikidataQueryBuilder values(String var, String... qids) {
        return valuesQids(var, Arrays.asList(qids));
    }

    public WikidataQueryBuilder valuesQids(
            String var, Collection<String> qids) {
        String values = wdList(qids);
        if (values.isBlank()) return this;
        where.append("  VALUES ").append(var(var))
             .append(" { ").append(values).append(" }\n");
        return this;
    }

    public WikidataQueryBuilder valuesRaw(
            String var, Collection<String> values) {
        if (values == null || values.isEmpty()) return this;
        where.append("  VALUES ").append(var(var)).append(" { ");
        for (String v : values)
            if (v != null && !v.isBlank()) where.append(v.trim()).append(" ");
        where.append("}\n");
        return this;
    }

    // ------------------------------------------------------------------
    // Raw where
    // ------------------------------------------------------------------

    public WikidataQueryBuilder rawWhere(String sparql) { return where(sparql); }

    public WikidataQueryBuilder where(String text) {
        if (text == null || text.isBlank()) return this;
        appendIndented(where, text, "  ");
        return this;
    }

    // ------------------------------------------------------------------
    // GROUP BY / HAVING / ORDER BY / LIMIT
    // ------------------------------------------------------------------

    public WikidataQueryBuilder groupBy(String... vars) {
        for (String v : vars) groupBy.add(var(v)); return this;
    }

    public WikidataQueryBuilder groupByRaw(String expression) {
        if (expression != null && !expression.isBlank())
            groupBy.add(expression.trim());
        return this;
    }

    public WikidataQueryBuilder having(String expression) {
        if (expression != null && !expression.isBlank()) {
            if (!having.isEmpty()) having.append("\n");
            having.append("HAVING(").append(expression.trim()).append(")");
        }
        return this;
    }

    public WikidataQueryBuilder orderBy(String... vars) {
        for (String v : vars) orderBy.add(var(v)); return this;
    }

    public WikidataQueryBuilder orderByRaw(String expression) {
        if (expression != null && !expression.isBlank())
            orderBy.add(expression.trim());
        return this;
    }

    public WikidataQueryBuilder offset(int offset) {
        this.offset = Math.max(0, offset); return this;
    }

    public WikidataQueryBuilder limit(int limit) {
        this.limit = Math.max(1, limit); return this;
    }

    public WikidataQueryBuilder noLimit() {
        this.limit = -1; return this;
    }

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    public String build() {
        StringBuilder q = new StringBuilder();
        q.append("SELECT ");
        if (distinct) q.append("DISTINCT ");
        if (select.isEmpty()) q.append("*");
        else {
            StringJoiner sj = new StringJoiner(" ");
            for (String s : select) sj.add(s);
            q.append(sj);
        }
        q.append(" WHERE {\n");
        q.append(where);
        if (!labelVars.isEmpty()) {
            q.append("  SERVICE wikibase:label {\n");
            q.append("    bd:serviceParam wikibase:language \"en\" .\n");
            q.append("  }\n");
        }
        q.append("}\n");
        if (!groupBy.isEmpty()) {
            q.append("GROUP BY ");
            for (String s : groupBy) q.append(s).append(" ");
            q.append("\n");
        }
        if (!having.isEmpty()) q.append(having).append("\n");
        if (!orderBy.isEmpty()) {
            q.append("ORDER BY ");
            for (String s : orderBy) q.append(s).append(" ");
            q.append("\n");
        }
        if (limit  >= 0) q.append("LIMIT ").append(limit).append("\n");
        if (offset >= 0) q.append("OFFSET ").append(offset).append("\n");
        return q.toString();
    }

    // ------------------------------------------------------------------
    // Term / var helpers
    // ------------------------------------------------------------------

    public String var(String name) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Variable name must not be blank");
        return name.startsWith("?") ? name : "?" + name;
    }

    public String term(String s) {
        if (s == null || s.isBlank())
            throw new IllegalArgumentException("SPARQL term must not be blank");
        if (s.startsWith("?")         || s.startsWith("wd:")
                || s.startsWith("wdt:")    || s.startsWith("p:")
                || s.startsWith("ps:")     || s.startsWith("pq:")
                || s.startsWith("schema:") || s.startsWith("wikibase:")
                || s.startsWith("bd:")     || s.startsWith("\"")
                || s.startsWith("<"))
            return s;
        return var(s);
    }

    private static void appendIndented(
            StringBuilder sb, String text, String indent) {
        if (text == null) return;
        for (String line : text.split("\\R"))
            if (!line.isBlank()) sb.append(indent).append(line).append("\n");
    }
}
