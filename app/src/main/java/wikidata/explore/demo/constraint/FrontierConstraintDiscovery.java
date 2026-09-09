package wikidata.explore.demo.constraint;

import wikidata.WikidataBinding;
import wikidata.WikidataIds;
import wikidata.WikidataSparqlClient;
import wikidata.query.LabelService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** One bounded shared-population frontier preview plus constraint profiling. */
public final class FrontierConstraintDiscovery {
    public enum Scope {
        CANDIDATE_ANCESTOR("Candidate ancestor"),
        CANDIDATE_NODE("Candidate node"),
        BRIDGE_NODE("Bridge node"),
        CANDIDATE_STATEMENT("Candidate statement");

        private final String label;
        Scope(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Constraint(Scope scope, String propertyPid, String valueQid,
                             String valueLabel, int sampleCount) {
        public Constraint {
            Objects.requireNonNull(scope, "scope");
            requirePid(propertyPid, "propertyPid");
            requireQid(valueQid, "valueQid");
            valueLabel = valueLabel == null || valueLabel.isBlank()
                    ? valueQid : valueLabel;
            if (sampleCount < 0) {
                throw new IllegalArgumentException("sampleCount must not be negative");
            }
        }
    }

    public record Edge(String sourceQid, String sourceLabel,
                       String bridgeQid, String bridgeLabel,
                       String candidateQid, String candidateLabel) { }

    public record Preview(List<Edge> edges, boolean limitReached) {
        public Preview {
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
        public List<String> candidateQids() {
            return edges.stream().map(Edge::candidateQid).distinct().toList();
        }
    }

    private FrontierConstraintDiscovery() { }

    public static Preview preview(WikidataSparqlClient client,
                                  List<String> frontierQids,
                                  String incomingPid,
                                  String outgoingPid,
                                  List<Constraint> constraints,
                                  int limit) throws Exception {
        requirePid(incomingPid, "incomingPid");
        requirePid(outgoingPid, "outgoingPid");
        List<String> frontier = qids(frontierQids);
        if (frontier.isEmpty()) return new Preview(List.of(), false);
        int requested = Math.max(1, limit);
        List<WikidataBinding> rows = client.query(previewQuery(
                frontier, incomingPid, outgoingPid, constraints, requested + 1));
        boolean limited = rows.size() > requested;
        if (limited) rows = rows.subList(0, requested);
        List<Edge> edges = new ArrayList<>();
        for (WikidataBinding row : rows) {
            String source = row.qid("source");
            String bridge = row.qid("bridge");
            String candidate = row.qid("candidate");
            if (source == null || bridge == null || candidate == null) continue;
            edges.add(new Edge(source, label(row, "source"),
                    bridge, label(row, "bridge"),
                    candidate, label(row, "candidate")));
        }
        return new Preview(edges, limited);
    }

    public static List<Constraint> discover(WikidataSparqlClient client,
                                            Preview preview,
                                            String outgoingPid,
                                            int limit) throws Exception {
        requirePid(outgoingPid, "outgoingPid");
        if (preview == null || preview.edges().isEmpty()) return List.of();
        List<WikidataBinding> rows = client.query(constraintQuery(
                preview.edges(), outgoingPid, Math.max(1, limit)));
        List<Constraint> result = new ArrayList<>();
        for (WikidataBinding row : rows) {
            Scope scope;
            try {
                scope = Scope.valueOf(row.value("scope"));
            } catch (RuntimeException invalid) {
                continue;
            }
            String property = row.qid("property");
            String value = row.qid("value");
            if (!WikidataIds.isPid(property) || !WikidataIds.isQid(value)) continue;
            int count;
            try {
                count = Integer.parseInt(row.value("count"));
            } catch (RuntimeException invalid) {
                count = 0;
            }
            result.add(new Constraint(scope, property, value,
                    label(row, "value"), count));
        }
        return List.copyOf(result);
    }

    static String previewQuery(List<String> frontierQids,
                               String incomingPid,
                               String outgoingPid,
                               List<Constraint> constraints,
                               int limit) {
        StringBuilder filters = new StringBuilder();
        for (Constraint constraint : constraints == null ? List.<Constraint>of() : constraints) {
            filters.append(switch (constraint.scope()) {
                case CANDIDATE_ANCESTOR -> "  ?candidate wdt:P279* wd:"
                        + constraint.valueQid() + ".\n";
                case CANDIDATE_NODE -> "  ?candidate wdt:"
                        + constraint.propertyPid() + " wd:" + constraint.valueQid() + ".\n";
                case BRIDGE_NODE -> "  ?bridge wdt:"
                        + constraint.propertyPid() + " wd:" + constraint.valueQid() + ".\n";
                case CANDIDATE_STATEMENT -> "  ?candidateStatement pq:"
                        + constraint.propertyPid() + " wd:" + constraint.valueQid() + ".\n";
            });
        }
        return """
                SELECT DISTINCT ?source ?sourceLabel ?bridge ?bridgeLabel
                                ?candidate ?candidateLabel WHERE {
                  VALUES ?source { %s }
                  ?bridge p:%s ?sourceStatement;
                          p:%s ?candidateStatement.
                  ?sourceStatement ps:%s ?source.
                  ?candidateStatement ps:%s ?candidate.
                  FILTER(?candidate != ?source)
                %s%s}
                LIMIT %d
                """.formatted(values(frontierQids), incomingPid, outgoingPid,
                incomingPid, outgoingPid, filters, LabelService.service(), limit);
    }

    static String constraintQuery(List<Edge> edges, String outgoingPid, int limit) {
        String candidates = values(edges.stream().map(Edge::candidateQid).distinct().toList());
        String bridges = values(edges.stream().map(Edge::bridgeQid).distinct().toList());
        String pairs = edges.stream()
                .map(edge -> "(wd:" + edge.bridgeQid() + " wd:" + edge.candidateQid() + ")")
                .distinct().reduce((left, right) -> left + " " + right).orElseThrow();
        return """
                SELECT ?scope ?property ?propertyLabel ?value ?valueLabel
                       (COUNT(DISTINCT ?sample) AS ?count) WHERE {
                  {
                    VALUES ?candidate { %s }
                    ?candidate wdt:P279+ ?value.
                    BIND(?candidate AS ?sample)
                    BIND(wd:P279 AS ?property)
                    BIND("CANDIDATE_ANCESTOR" AS ?scope)
                  } UNION {
                    VALUES ?candidate { %s }
                    ?candidate ?predicate ?value.
                    ?property wikibase:directClaim ?predicate.
                    FILTER(isIRI(?value))
                    FILTER(?property != wd:P279)
                    BIND(?candidate AS ?sample)
                    BIND("CANDIDATE_NODE" AS ?scope)
                  } UNION {
                    VALUES ?bridge { %s }
                    ?bridge ?predicate ?value.
                    ?property wikibase:directClaim ?predicate.
                    FILTER(isIRI(?value))
                    BIND(?bridge AS ?sample)
                    BIND("BRIDGE_NODE" AS ?scope)
                  } UNION {
                    VALUES (?bridge ?candidate) { %s }
                    ?bridge p:%s ?candidateStatement.
                    ?candidateStatement ps:%s ?candidate;
                                        ?qualifierPredicate ?value.
                    ?property wikibase:qualifier ?qualifierPredicate.
                    FILTER(isIRI(?value))
                    BIND(?candidateStatement AS ?sample)
                    BIND("CANDIDATE_STATEMENT" AS ?scope)
                  }
                %s}
                GROUP BY ?scope ?property ?propertyLabel ?value ?valueLabel
                ORDER BY DESC(?count) ?scope ?propertyLabel ?valueLabel
                LIMIT %d
                """.formatted(candidates, candidates, bridges, pairs,
                outgoingPid, outgoingPid, LabelService.service(), limit);
    }

    private static List<String> qids(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) values.stream().filter(WikidataIds::isQid).forEach(result::add);
        return List.copyOf(result);
    }

    private static String values(List<String> qids) {
        return qids.stream().map(qid -> "wd:" + qid)
                .reduce((left, right) -> left + " " + right).orElseThrow();
    }

    private static String label(WikidataBinding row, String variable) {
        String value = row.label(variable);
        return value == null || value.isBlank() ? row.qid(variable) : value;
    }

    private static void requirePid(String value, String name) {
        if (!WikidataIds.isPid(value)) throw new IllegalArgumentException(name + " must be a PID");
    }

    private static void requireQid(String value, String name) {
        if (!WikidataIds.isQid(value)) throw new IllegalArgumentException(name + " must be a QID");
    }
}
