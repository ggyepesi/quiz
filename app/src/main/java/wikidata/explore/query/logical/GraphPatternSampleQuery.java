package wikidata.explore.query.logical;

import datasource.graph.GraphExpansionPattern;
import wikidata.WikidataBinding;
import wikidata.WikidataIds;
import wikidata.WikimediaInternalTypes;
import wikidata.explore.query.core.Datasource;
import wikidata.explore.query.core.WikidataAccess;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only, bounded preview of one configured Wikidata graph expansion. */
public final class GraphPatternSampleQuery
        implements Query<GraphPatternSampleQuery.Result> {

    public record Node(String qid, String label) { }
    public record Edge(String statementId, Node source, Node target) { }
    public record Result(List<Node> selected, List<Node> sources,
                         List<Edge> statements, List<Node> targets) {
        public Result {
            selected = List.copyOf(selected);
            sources = List.copyOf(sources);
            statements = List.copyOf(statements);
            targets = List.copyOf(targets);
        }
    }

    private final GraphExpansionPattern pattern;
    private final List<String> seeds;
    private final int limit;

    public GraphPatternSampleQuery(GraphExpansionPattern pattern,
                                   List<String> seeds, int limit) {
        this.pattern = java.util.Objects.requireNonNull(pattern, "pattern");
        this.seeds = seeds == null ? List.of() : seeds.stream()
                .map(String::trim).filter(WikidataIds::isQid).distinct().toList();
        if (this.seeds.isEmpty()) throw new IllegalArgumentException(
                "Select at least one valid expansion-node QID.");
        this.limit = Math.max(1, Math.min(200, limit));
        if (!WikidataIds.isPid(pattern.relation().relationId())) {
            throw new IllegalArgumentException("The graph relation is not a Wikidata PID.");
        }
    }

    @Override public String purpose() { return "Preview graph-pattern expansion"; }
    @Override public String queryType() { return "SPARQL"; }
    @Override public String skeleton() {
        return "selected target -> reverse subjects -> complete relation statements -> targets";
    }
    @Override public String description() {
        return "Samples the configured expansion without changing the model or snapshot.";
    }
    @Override public Map<String, String> parameters() {
        return Map.of("pattern", pattern.id(), "seeds", String.join(",", seeds),
                "limit", String.valueOf(limit));
    }

    @Override public Result execute(QueryContext context) throws Exception {
        String pid = pattern.relation().relationId();
        String values = seeds.stream().map(qid -> "wd:" + qid)
                .collect(java.util.stream.Collectors.joining(" "));
        // This is intentionally the statement-node leg rather than two wdt: edges:
        // the preview must demonstrate the same reified edges generation constructs.
        String sparql = "SELECT ?expanded ?expandedLabel ?source ?sourceLabel "
                + "?statement ?target ?targetLabel WHERE {\n"
                + "  VALUES ?expanded { " + values + " }\n"
                + "  ?source wdt:" + pid + " ?expanded .\n"
                + "  ?source p:" + pid + " ?statement .\n"
                + "  ?statement ps:" + pid + " ?target .\n"
                + WikimediaInternalTypes.excludeExclusivelyInternal("?target")
                + "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"en,mul\". }\n"
                // An explanation the reader may run twice must not answer
                // differently the second time: LIMIT without an order leaves which
                // sample survives to the query planner. Measured against WDQS, the
                // ordering costs nothing outside noise on this bounded shape.
                + "}\nORDER BY ?source ?statement\nLIMIT " + limit;
        return context.step(purpose(), "SPARQL", skeleton(), parameters(), step -> {
            step.request(sparql);
            Map<String, Node> selected = new LinkedHashMap<>();
            Map<String, Node> sources = new LinkedHashMap<>();
            Map<String, Node> targets = new LinkedHashMap<>();
            Map<String, Edge> edges = new LinkedHashMap<>();
            for (WikidataBinding binding : WikidataAccess
                    .sparql(context, Datasource.WIKIDATA).query(sparql)) {
                Node expanded = node(binding, "expanded");
                Node source = node(binding, "source");
                Node target = node(binding, "target");
                if (expanded != null) selected.putIfAbsent(expanded.qid(), expanded);
                if (source == null || target == null) continue;
                sources.putIfAbsent(source.qid(), source);
                targets.putIfAbsent(target.qid(), target);
                String statement = tail(binding.value("statement"));
                if (statement.isBlank()) statement = source.qid() + ":" + target.qid();
                edges.putIfAbsent(statement, new Edge(statement, source, target));
            }
            // A valid empty adjacency still shows what was requested.
            for (String seed : seeds) selected.putIfAbsent(seed, new Node(seed, seed));
            Result result = new Result(new ArrayList<>(selected.values()),
                    new ArrayList<>(sources.values()), new ArrayList<>(edges.values()),
                    new ArrayList<>(targets.values()));
            step.summary(result.statements().size() + " statement sample(s), "
                    + result.sources().size() + " source node(s), "
                    + result.targets().size() + " target node(s)");
            return result;
        });
    }

    @Override public int rowCount(Result result) {
        return result == null ? 0 : result.statements().size();
    }
    @Override public String summary(Result result) {
        return rowCount(result) + " sampled statement(s)";
    }

    private static Node node(WikidataBinding binding, String variable) {
        String qid = binding.qid(variable);
        if (!WikidataIds.isQid(qid)) return null;
        String label = binding.label(variable);
        return new Node(qid, label == null || label.isBlank() ? qid : label);
    }

    private static String tail(String value) {
        if (value == null) return "";
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }
}
