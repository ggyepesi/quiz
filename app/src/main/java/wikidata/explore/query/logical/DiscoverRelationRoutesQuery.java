package wikidata.explore.query.logical;

import domain.datasource.graph.GraphTraversalDirection;
import domain.datasource.graph.RelationRoute;
import domain.datasource.wikidata.WikidataRouteRelation;
import batch.BatchFailure;
import wikidata.WikidataBatchFailureClassifier;
import wikidata.WikidataBinding;
import wikidata.WikidataIds;
import domain.analysis.RelationRouteDiscovery;
import wikidata.query.core.Datasource;
import wikidata.query.core.WikidataAccess;
import wikidata.statement.EntityStatementSummary;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Discovers bounded typed relation routes between explicit Wikidata examples. */
public final class DiscoverRelationRoutesQuery
        implements Query<RelationRouteDiscovery.Result> {
    static final int INCOMING_EDGES_PER_ENTITY = 100;
    static final int INCOMING_ENTITIES_PER_WAVE = 25;
    private final List<RelationRouteDiscovery.Example> examples;
    private final int maximumSteps;
    private final int maximumEntities;
    private int wave;

    public DiscoverRelationRoutesQuery(
            List<RelationRouteDiscovery.Example> examples,
            int maximumSteps,
            int maximumEntities) {
        this.examples = examples == null ? List.of() : List.copyOf(examples);
        if (this.examples.isEmpty()) {
            throw new IllegalArgumentException(
                    "Enter at least one source → expected target example");
        }
        this.maximumSteps = Math.max(1, Math.min(6, maximumSteps));
        this.maximumEntities = Math.max(this.examples.size(),
                Math.min(5_000, maximumEntities));
    }

    @Override public String purpose() { return "Discover bounded relation routes"; }
    @Override public String queryType() { return "Bounded SPARQL waves"; }
    @Override public String skeleton() {
        return "source QID --property / statement+qualifier--> expected target QID";
    }
    @Override public String description() {
        return "Read-only route discovery over explicit source/target examples; "
                + "only Add selected route changes field configuration.";
    }
    @Override public Map<String, String> parameters() {
        return Map.of("examples", Integer.toString(examples.stream()
                        .mapToInt(example -> example.expectedTargetQids().size()).sum()),
                "maximumSteps", Integer.toString(maximumSteps),
                "maximumQids", Integer.toString(maximumEntities));
    }

    @Override public RelationRouteDiscovery.Result execute(QueryContext context)
            throws Exception {
        wave = 0;
        return RelationRouteDiscovery.discover(examples, maximumSteps, maximumEntities,
                qids -> readSegments(context, qids));
    }

    private RelationRouteDiscovery.ReadResult readSegments(
            QueryContext context, List<String> qids) throws Exception {
        context.cancellation().throwIfCancelled();
        int currentWave = ++wave;
        String outgoing = EntityStatementSummary.routeQuery(qids);
        List<EntityStatementSummary> summaries = context.step(
                "Route wave " + currentWave + ": statements and qualifiers",
                "SPARQL", skeleton(),
                Map.of("frontierQids", Integer.toString(qids.size())), step -> {
                    step.request(outgoing);
                    List<WikidataBinding> rows = WikidataAccess
                            .sparql(context, Datasource.WIKIDATA).query(outgoing);
                    step.summary(rows.size() + " statement/qualifier row(s)");
                    return EntityStatementSummary.fromRows(qids, rows);
                });

        Map<String, LinkedHashSet<RelationRouteDiscovery.Segment>> byQid =
                new LinkedHashMap<>();
        qids.forEach(qid -> byQid.put(qid, new LinkedHashSet<>()));
        for (EntityStatementSummary summary : summaries) {
            LinkedHashSet<RelationRouteDiscovery.Segment> segments =
                    byQid.computeIfAbsent(summary.qid(), ignored -> new LinkedHashSet<>());
            addOutgoing(summary, segments);
        }
        boolean incomingLimited = addIncoming(context, currentWave, qids, byQid);
        Map<String, List<RelationRouteDiscovery.Segment>> frozen = new LinkedHashMap<>();
        byQid.forEach((qid, segments) -> frozen.put(qid, List.copyOf(segments)));
        return new RelationRouteDiscovery.ReadResult(frozen, incomingLimited);
    }

    /**
     * Incoming relations are intentionally read one entity at a time. A shared VALUES
     * query let a common target such as human consume the whole limit (and forced WDQS
     * to sort millions of rows before applying it). A capped or timed-out entity is too
     * broad to be a useful route-discovery intermediate, so none of its arbitrary first
     * rows are admitted and the bounded run continues.
     */
    private boolean addIncoming(
            QueryContext context,
            int currentWave,
            List<String> qids,
            Map<String, LinkedHashSet<RelationRouteDiscovery.Segment>> byQid)
            throws Exception {
        boolean limited = qids.size() > INCOMING_ENTITIES_PER_WAVE;
        List<String> targets = qids.stream()
                .limit(INCOMING_ENTITIES_PER_WAVE).toList();
        if (limited) {
            context.message("Route wave " + currentWave + ": skipped incoming reads for "
                    + (qids.size() - targets.size())
                    + " frontier QID(s); per-wave limit is "
                    + INCOMING_ENTITIES_PER_WAVE + ".\n");
        }
        for (String qid : targets) {
            context.cancellation().throwIfCancelled();
            String incoming = incomingQuery(qid, INCOMING_EDGES_PER_ENTITY + 1);
            IncomingAttempt attempt = context.step(
                    "Route wave " + currentWave + ": incoming properties for " + qid,
                    "SPARQL", skeleton(),
                    Map.of("targetQid", qid, "rowLimit",
                            Integer.toString(INCOMING_EDGES_PER_ENTITY)), step -> {
                        step.request(incoming);
                        try {
                            List<WikidataBinding> rows = WikidataAccess
                                    .sparql(context, Datasource.WIKIDATA).query(incoming);
                            boolean capped = rows.size() > INCOMING_EDGES_PER_ENTITY;
                            step.summary(capped
                                    ? "more than " + INCOMING_EDGES_PER_ENTITY
                                            + " incoming edges — skipped as too broad"
                                    : rows.size() + " incoming edge row(s)");
                            return new IncomingAttempt(capped ? List.of() : rows, capped);
                        } catch (Exception failure) {
                            if (!incomingFailureIsLimit(failure)) throw failure;
                            step.summary("timed out or too broad — incoming edges skipped");
                            return new IncomingAttempt(List.of(), true);
                        }
                    });
            limited |= attempt.limited();
            if (attempt.limited()) {
                context.message("Route wave " + currentWave + ": incoming relations for "
                        + qid + " were skipped because the entity is too broad or the "
                        + "bounded read timed out.\n");
                continue;
            }
            for (WikidataBinding row : attempt.rows()) {
                String target = row.qid("target");
                String source = row.qid("source");
                String pid = row.qid("prop");
                if (!qid.equals(target) || !WikidataIds.isQid(source)
                        || !WikidataIds.isPid(pid)) continue;
                String propertyLabel = label(row.label("prop"), pid);
                byQid.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(
                        new RelationRouteDiscovery.Segment(
                                List.of(propertyStep(pid,
                                        GraphTraversalDirection.INCOMING)),
                                List.of("incoming " + propertyLabel + " (" + pid + ")"),
                                source, label(row.label("source"), source)));
            }
        }
        return limited;
    }

    static boolean incomingFailureIsLimit(Throwable failure) {
        if (WikidataBatchFailureClassifier.INSTANCE.classify(failure).failure()
                == BatchFailure.TOO_HEAVY) return true;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof wikidata.WikidataSparqlClient.SparqlHttpException http
                    && (http.statusCode() == 500 || http.statusCode() == 503)) {
                String message = current.getMessage() == null ? ""
                        : current.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (message.contains("timeout") || message.contains("timed out")) {
                    return true;
                }
            }
        }
        return false;
    }

    private record IncomingAttempt(List<WikidataBinding> rows, boolean limited) { }

    static void addOutgoing(
            EntityStatementSummary summary,
            Set<RelationRouteDiscovery.Segment> segments) {
        for (EntityStatementSummary.Property property : summary.properties()) {
            String propertyLabel = label(property.label(), property.pid());
            // Generation reads a direct property's truthy values: the preferred
            // statements when there are any, otherwise the normal ones. A statement step
            // reads every non-deprecated statement, which the route query already returns.
            boolean preferredExists = property.statements().stream()
                    .anyMatch(statement -> "preferred".equals(statement.rank()));
            for (EntityStatementSummary.Statement statement : property.statements()) {
                boolean truthy = !preferredExists || "preferred".equals(statement.rank());
                if (truthy && WikidataIds.isQid(statement.valueQid())) {
                    segments.add(new RelationRouteDiscovery.Segment(
                            List.of(propertyStep(property.pid(),
                                    GraphTraversalDirection.OUTGOING)),
                            List.of(propertyLabel + " (" + property.pid() + ")"),
                            statement.valueQid(),
                            label(statement.value(), statement.valueQid())));
                }
                for (EntityStatementSummary.Qualifier qualifier
                        : statement.qualifiers()) {
                    if (!WikidataIds.isQid(qualifier.valueQid())) continue;
                    segments.add(new RelationRouteDiscovery.Segment(
                            List.of(
                                    typedStep(WikidataRouteRelation.Kind.STATEMENT,
                                            property.pid()),
                                    typedStep(WikidataRouteRelation.Kind.QUALIFIER,
                                            qualifier.pid())),
                            List.of("statement " + propertyLabel + " ("
                                            + property.pid() + ")",
                                    "qualifier " + label(qualifier.label(), qualifier.pid())
                                            + " (" + qualifier.pid() + ")"),
                            qualifier.valueQid(),
                            label(qualifier.value(), qualifier.valueQid())));
                }
            }
        }
    }

    private static RelationRoute.Step propertyStep(
            String pid, GraphTraversalDirection direction) {
        return new RelationRoute.Step(
                new WikidataRouteRelation(WikidataRouteRelation.Kind.PROPERTY, pid)
                        .relation(), direction);
    }

    private static RelationRoute.Step typedStep(
            WikidataRouteRelation.Kind kind, String pid) {
        return new RelationRoute.Step(new WikidataRouteRelation(kind, pid).relation(),
                GraphTraversalDirection.OUTGOING);
    }

    static String incomingQuery(String qid, int limit) {
        if (!WikidataIds.isQid(qid)) {
            throw new IllegalArgumentException("Invalid incoming target QID: " + qid);
        }
        return "SELECT DISTINCT ?target ?source ?sourceLabel ?prop ?propLabel WHERE {\n"
                + "  VALUES ?target { wd:" + qid + " }\n"
                + "  ?source ?predicate ?target .\n"
                + "  ?prop wikibase:directClaim ?predicate .\n"
                + "  FILTER(STRSTARTS(STR(?source), "
                + "\"http://www.wikidata.org/entity/\"))\n  "
                + wikidata.query.LabelService.service()
                + "}\nLIMIT " + Math.max(1, limit);
    }

    public static List<RelationRouteDiscovery.Example> parseExamples(String text) {
        List<RelationRouteDiscovery.Example> parsed = new ArrayList<>();
        String[] lines = (text == null ? "" : text).split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) continue;
            String[] sides = line.split("\\s*(?:->|→)\\s*", -1);
            if (sides.length != 2 || !WikidataIds.isQid(sides[0].trim())) {
                throw new IllegalArgumentException("Line " + (index + 1)
                        + " must read Qsource → Qtarget [Qtarget …]");
            }
            List<String> targets = java.util.Arrays.stream(sides[1].split("[\\s,]+"))
                    .map(String::trim).filter(value -> !value.isEmpty()).toList();
            if (targets.isEmpty() || targets.stream().anyMatch(
                    target -> !WikidataIds.isQid(target))) {
                throw new IllegalArgumentException("Line " + (index + 1)
                        + " has an invalid expected target QID");
            }
            parsed.add(new RelationRouteDiscovery.Example(sides[0], targets));
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(
                    "Enter at least one line such as Q22878 → Q12748409 Q3991395");
        }
        return List.copyOf(parsed);
    }

    private static String label(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Override public int rowCount(RelationRouteDiscovery.Result result) {
        return result == null ? 0 : result.candidates().size();
    }

    @Override public String summary(RelationRouteDiscovery.Result result) {
        if (result == null) return "0 routes";
        return result.candidates().size() + " route(s), " + result.entitiesInspected()
                + " QID(s) inspected"
                + (result.discoveryLimitReached()
                        ? " — bounded search skipped broad, timed-out, or excess branches"
                        : "");
    }
}
