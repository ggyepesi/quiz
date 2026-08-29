package wikidata.explore.query.logical;

import wikidata.WikidataBinding;
import wikidata.WikidataIds;
import wikidata.explore.query.core.Datasource;
import wikidata.explore.query.core.WikidataAccess;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Bounded discovery of QID nodes connected through one selected Wikidata property. */
public final class DiscoverEntityRelationQuery
        implements Query<DiscoverEntityRelationQuery.Result> {
    public enum Direction { OUTGOING, INCOMING }
    public record Node(String qid, String label, int depth) { }
    public record Edge(String sourceQid, String targetQid) { }
    public record Result(String pid, Direction direction, List<Node> nodes,
                         List<Edge> edges, boolean discoveryLimitReached) {
        public Result { nodes = List.copyOf(nodes); edges = List.copyOf(edges); }
    }

    private final String pid;
    private final List<String> seeds;
    private final Direction direction;
    private final int maxDepth;
    private final int maxNodes;

    public DiscoverEntityRelationQuery(String pid, List<String> seeds,
                                       Direction direction, int maxDepth, int maxNodes) {
        if (!WikidataIds.isPid(pid)) throw new IllegalArgumentException(
                "Select a valid Wikidata property.");
        this.pid = pid;
        this.seeds = seeds == null ? List.of() : seeds.stream().map(String::trim)
                .filter(WikidataIds::isQid).distinct().toList();
        if (this.seeds.isEmpty()) throw new IllegalArgumentException(
                "Enter at least one starting QID.");
        this.direction = direction == null ? Direction.OUTGOING : direction;
        this.maxDepth = Math.max(0, Math.min(12, maxDepth));
        this.maxNodes = Math.max(this.seeds.size(), Math.min(5_000, maxNodes));
    }

    @Override public String purpose() { return "Explore QIDs connected through " + pid; }
    @Override public String queryType() { return "SPARQL waves"; }
    @Override public String skeleton() { return "QID --" + pid + "--> QID"; }
    @Override public String description() {
        return "Read-only relation discovery; the selected PID is the edge, not a node.";
    }
    @Override public Map<String, String> parameters() {
        return Map.of("property", pid, "startingQids", String.join(",", seeds),
                "direction", direction.name(), "maxDepth", Integer.toString(maxDepth),
                "maxNodes", Integer.toString(maxNodes));
    }

    @Override public Result execute(QueryContext context) throws Exception {
        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        seeds.forEach(qid -> nodes.put(qid, new Node(qid, qid, 0)));
        LinkedHashSet<Edge> edges = new LinkedHashSet<>();
        LinkedHashSet<String> frontier = new LinkedHashSet<>(seeds);
        boolean limited = false;
        for (int depth = 1; depth <= maxDepth && !frontier.isEmpty(); depth++) {
            context.cancellation().throwIfCancelled();
            int rowLimit = Math.max(1, maxNodes * 4);
            String sparql = query(frontier, rowLimit + 1);
            int waveDepth = depth;
            List<WikidataBinding> rows = context.step("Relation wave " + depth, "SPARQL",
                    skeleton(), Map.of("depth", Integer.toString(depth),
                            "frontier", Integer.toString(frontier.size())), step -> {
                        step.request(sparql);
                        List<WikidataBinding> answer = WikidataAccess
                                .sparql(context, Datasource.WIKIDATA).query(sparql);
                        step.summary(answer.size() + " direct edge row(s)");
                        return answer;
                    });
            if (rows.size() > rowLimit) limited = true;
            LinkedHashSet<String> next = new LinkedHashSet<>();
            for (WikidataBinding row : rows.stream().limit(rowLimit).toList()) {
                String source = row.qid("source"), target = row.qid("target");
                if (!WikidataIds.isQid(source) || !WikidataIds.isQid(target)) continue;
                String anchor = direction == Direction.OUTGOING ? source : target;
                if (!frontier.contains(anchor)) continue;
                putLabel(nodes, anchor, direction == Direction.OUTGOING
                        ? row.label("source") : row.label("target"));
                String adjacent = direction == Direction.OUTGOING ? target : source;
                if (!nodes.containsKey(adjacent)) {
                    if (nodes.size() >= maxNodes) { limited = true; continue; }
                    String label = direction == Direction.OUTGOING
                            ? row.label("target") : row.label("source");
                    nodes.put(adjacent, new Node(adjacent, label(label, adjacent), waveDepth));
                    next.add(adjacent);
                } else putLabel(nodes, adjacent, direction == Direction.OUTGOING
                        ? row.label("target") : row.label("source"));
                edges.add(new Edge(source, target));
            }
            frontier = next;
            if (limited) break;
        }
        return new Result(pid, direction, new ArrayList<>(nodes.values()),
                new ArrayList<>(edges), limited);
    }

    private String query(LinkedHashSet<String> frontier, int limit) {
        String values = frontier.stream().map(qid -> "wd:" + qid)
                .collect(Collectors.joining(" "));
        String relation = direction == Direction.OUTGOING
                ? "VALUES ?source { " + values + " }\n  ?source wdt:" + pid + " ?target ."
                : "VALUES ?target { " + values + " }\n  ?source wdt:" + pid + " ?target .";
        return "SELECT DISTINCT ?source ?sourceLabel ?target ?targetLabel WHERE {\n  "
                + relation + "\n  " + wikidata.query.LabelService.service()
                + "}\nORDER BY ?source ?target\nLIMIT " + limit;
    }

    private static void putLabel(Map<String, Node> nodes, String qid, String label) {
        Node old = nodes.get(qid);
        if (old == null) return;
        String display = label(label, qid);
        if (old.label().equals(old.qid()) && !display.equals(qid))
            nodes.put(qid, new Node(qid, display, old.depth()));
    }
    private static String label(String value, String qid) {
        return value == null || value.isBlank() ? qid : value;
    }
    @Override public int rowCount(Result result) { return result.edges().size(); }
    @Override public String summary(Result result) {
        return result.nodes().size() + " QID node(s), " + result.edges().size() + " edge(s)"
                + (result.discoveryLimitReached() ? " — discovery limit reached" : "");
    }
}
