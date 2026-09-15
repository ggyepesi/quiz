package wikidata.statement;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A concise, statement-centric summary of ONE Wikidata entity — the "Statements"
 * section of its wiki page (e.g. {@code Q137855674}), grouped property → values,
 * with each statement's <em>qualifiers</em> nested underneath.
 *
 * <p>This is the read-only core of the example-first modeling idea: seeing a real
 * entity's statements-with-qualifiers is what makes qualifier fields (year P585,
 * for-work P1686, edition P805) obvious — they're invisible in a flat property
 * list. A later slice turns a clicked statement/qualifier into a pre-filled field.
 *
 * <p>Single entity, so the query (all claims + their qualifiers, labelled) is
 * bounded. Values render as {@code label [Qid]} for items, or a shortened literal
 * (year for a Jan-1 time value) otherwise.
 */
public final class EntityStatementSummary {

    public record Qualifier(String pid, String label, String value, String valueQid) { }

    public record Statement(
            String id, String value, String valueQid, List<Qualifier> qualifiers) { }

    public record Property(String pid, String label, List<Statement> statements) { }

    private final String qid;
    private final List<Property> properties;

    private EntityStatementSummary(String qid, List<Property> properties) {
        this.qid = qid;
        this.properties = properties;
    }

    public String qid() { return qid; }

    public List<Property> properties() { return properties; }

    public static EntityStatementSummary of(String qid, List<Property> properties) {
        return new EntityStatementSummary(qid,
                properties == null ? List.of() : List.copyOf(properties));
    }

    private static final String ENTITY_PREFIX = "http://www.wikidata.org/entity/";

    private static final String QUERY = """
            PREFIX wd: <http://www.wikidata.org/entity/>
            PREFIX wikibase: <http://wikiba.se/ontology#>
            PREFIX bd: <http://www.bigdata.com/rdf#>
            SELECT ?e ?prop ?propLabel ?st ?val ?valLabel ?qualp ?qualpLabel ?qv ?qvLabel WHERE {
              VALUES ?e { %s }
              ?e ?p ?st .
              ?prop wikibase:claim ?p .
              ?prop wikibase:statementProperty ?ps .
              ?st ?ps ?val .
              OPTIONAL {
                ?st ?pq ?qv .
                ?qualp wikibase:qualifier ?pq .
              }
            %s
            }
            ORDER BY ?prop ?st
            """;

    public static EntityStatementSummary fetch(String qid, WikidataSparqlClient client)
            throws Exception {
        List<WikidataBinding> rows = client.query(query(List.of(qid)));
        return fromRows(qid, rows);
    }

    /** One bounded VALUES query for several entities; used by the adaptive batch executor. */
    public static String query(List<String> qids) {
        String values = (qids == null ? List.<String>of() : qids).stream()
                .map(qid -> "wd:" + qid).collect(java.util.stream.Collectors.joining(" "));
        return QUERY.formatted(values, wikidata.query.LabelService.service());
    }

    /** Restores one summary per requested QID, including entities with no returned rows. */
    public static List<EntityStatementSummary> fromRows(
            List<String> qids, List<WikidataBinding> rows) {
        Map<String, List<WikidataBinding>> byEntity = new LinkedHashMap<>();
        if (qids != null) for (String qid : qids) byEntity.putIfAbsent(qid, new ArrayList<>());
        if (rows != null) for (WikidataBinding row : rows) {
            String qid = row.qid("e");
            if (qid != null) byEntity.computeIfAbsent(qid, ignored -> new ArrayList<>()).add(row);
        }
        return byEntity.entrySet().stream()
                .map(entry -> fromRows(entry.getKey(), entry.getValue())).toList();
    }

    private static EntityStatementSummary fromRows(
            String qid, List<WikidataBinding> rows) {

        // property PID -> (statement node -> aggregated statement)
        Map<String, PropAgg> byProp = new LinkedHashMap<>();
        for (WikidataBinding b : rows) {
            String propPid = b.qid("prop");
            if (propPid == null) {
                continue;
            }
            PropAgg pa = byProp.computeIfAbsent(propPid,
                    k -> new PropAgg(propPid, orPid(b.label("prop"), propPid)));

            String statementId = b.value("st");
            StAgg sa = pa.statements.computeIfAbsent(statementId, k -> new StAgg());
            sa.id = statementId;
            if (sa.value == null) {
                String valUri = b.value("val");
                if (isEntity(valUri)) {
                    sa.valueQid = b.qid("val");
                    sa.value = orPid(b.label("val"), sa.valueQid);
                } else {
                    sa.value = shortLiteral(valUri);
                }
            }

            String qualPid = b.qid("qualp");
            if (qualPid != null && !qualPid.isBlank()) {
                String qvUri = b.value("qv");
                String qualifierQid = isEntity(qvUri) ? b.qid("qv") : null;
                String qv = qualifierQid != null
                        ? orPid(b.label("qv"), qualifierQid) : shortLiteral(qvUri);
                sa.qualifiers.putIfAbsent(qualPid + "=" + qv,
                        new Qualifier(qualPid, orPid(b.label("qualp"), qualPid),
                                qv, qualifierQid));
            }
        }

        List<Property> props = new ArrayList<>();
        for (PropAgg pa : byProp.values()) {
            List<Statement> sts = new ArrayList<>();
            for (StAgg sa : pa.statements.values()) {
                sts.add(new Statement(sa.id, sa.value, sa.valueQid,
                        new ArrayList<>(sa.qualifiers.values())));
            }
            props.add(new Property(pa.pid, pa.label, sts));
        }
        return new EntityStatementSummary(qid, props);
    }

    /** A compact text rendering — the shape a modeler scans before configuring. */
    public String concise() {
        StringBuilder sb = new StringBuilder(qid).append('\n');
        for (Property p : properties) {
            sb.append("  ").append(p.label()).append("  (").append(p.pid()).append(')');
            if (p.statements().size() > 1) {
                sb.append("  ×").append(p.statements().size());
            }
            sb.append('\n');
            for (Statement s : p.statements()) {
                sb.append("      • ").append(s.value());
                if (s.valueQid() != null) {
                    sb.append("  [").append(s.valueQid()).append(']');
                }
                sb.append('\n');
                for (Qualifier q : s.qualifiers()) {
                    sb.append("          ↳ ").append(q.label())
                      .append(" (").append(q.pid()).append("): ").append(q.value()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static boolean isEntity(String uri) {
        return uri != null && uri.startsWith(ENTITY_PREFIX);
    }

    private static String orPid(String label, String pid) {
        return label == null || label.isBlank() ? pid : label;
    }

    /** Shorten a literal for display: a Jan-1 time value → its year; any other
     *  time value → its date; everything else verbatim. */
    private static String shortLiteral(String v) {
        if (v == null) {
            return "";
        }
        if (v.matches("[+-]?\\d{4,}-\\d{2}-\\d{2}T.*")) {
            String date = v.replaceFirst("T.*", "").replaceFirst("^\\+", "");
            return date.endsWith("-01-01") ? date.substring(0, date.indexOf('-', 1)) : date;
        }
        return v;
    }

    private static final class PropAgg {
        final String pid;
        final String label;
        final Map<String, StAgg> statements = new LinkedHashMap<>();

        PropAgg(String pid, String label) {
            this.pid = pid;
            this.label = label;
        }
    }

    private static final class StAgg {
        String id;
        String value;
        String valueQid;
        final Map<String, Qualifier> qualifiers = new LinkedHashMap<>();
    }
}
