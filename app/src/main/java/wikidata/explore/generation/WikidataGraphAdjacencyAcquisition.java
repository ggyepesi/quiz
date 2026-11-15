package wikidata.explore.generation;

import batch.BatchCheckpointStore;
import batch.BatchExecutor;
import batch.BatchPolicy;
import batch.WorkDescriptor;
import batch.WorkUnit;
import datasource.EntityRef;
import datasource.GraphValue;
import datasource.LiteralValue;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.execution.GraphAdjacencyAcquirer;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.GraphEdge;
import datasource.graph.store.LocalGraphStore;
import wikidata.WikidataBatchFailureClassifier;
import wikidata.WikidataBinding;
import wikidata.WikidataIds;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.GenerationLog;
import work.CancellationToken;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Acquires Wikidata adjacency into the shared graph store in either direction. */
public final class WikidataGraphAdjacencyAcquisition implements GraphAdjacencyAcquirer {
    private static final String PROVIDER = "wikidata";
    private static final String STATEMENT_NAMESPACE = "wikidata-statement";
    private static final int ENTITY_BATCH_SIZE = 50;
    private static final int REVERSE_BATCH_SIZE = 50;

    private final WikidataApiClient api;
    private final WikidataSparqlClient sparql;
    private final GenerationLog log;
    private final CancellationToken cancellation;
    /** Statements a statement step read, by GUID. The entity response that produced a
     * statement node already carries all of its qualifiers, so a following qualifier step
     * reads them here instead of requesting the same entities and property again. */
    private final Map<String, WikidataApiClient.ApiStatement> retainedStatements =
            new java.util.HashMap<>();

    public WikidataGraphAdjacencyAcquisition(
            WikidataApiClient api,
            WikidataSparqlClient sparql,
            GenerationLog log,
            CancellationToken cancellation) {
        this.api = api;
        this.sparql = sparql;
        this.log = log == null ? GenerationLog.NOOP : log;
        this.cancellation = cancellation == null ? new CancellationToken() : cancellation;
    }

    @Override public void acquire(
            LocalGraphStore store, GraphAdjacencyDemand demand) throws Exception {
        if (store == null || demand == null) {
            throw new IllegalArgumentException("Graph store and adjacency demand are required");
        }
        if (statementProperty(demand.relation()) != null) {
            acquireStatements(store, demand);
            return;
        }
        if (qualifierProperty(demand.relation()) != null) {
            acquireQualifiers(store, demand);
            return;
        }
        requireWikidata(demand.relation(), demand.nodes());
        if (demand.nodes().isEmpty()) return;
        if (demand.direction() == GraphTraversalDirection.OUTGOING) {
            acquireOutgoing(store, demand);
        } else {
            acquireIncoming(store, demand);
        }
    }

    @Override public void acquireAll(
            LocalGraphStore store,
            Collection<GraphAdjacencyDemand> demands) throws Exception {
        if (store == null) throw new IllegalArgumentException("Graph store is required");
        if (demands == null || demands.isEmpty()) return;
        List<GraphAdjacencyDemand> requested = demands.stream()
                .filter(java.util.Objects::nonNull)
                .filter(demand -> !demand.nodes().isEmpty())
                .distinct()
                .toList();
        for (GraphAdjacencyDemand demand : requested) {
            if (statementProperty(demand.relation()) != null
                    || qualifierProperty(demand.relation()) != null) {
                acquire(store, demand);
            } else {
                requireWikidata(demand.relation(), demand.nodes());
            }
        }
        Map<EntityRef, LinkedHashSet<GraphRelation>> outgoingByNode =
                new LinkedHashMap<>();
        for (GraphAdjacencyDemand demand : requested) {
            if (statementProperty(demand.relation()) != null
                    || qualifierProperty(demand.relation()) != null) continue;
            if (demand.direction() == GraphTraversalDirection.INCOMING) {
                acquireIncoming(store, demand);
                continue;
            }
            for (EntityRef node : demand.nodes()) {
                outgoingByNode.computeIfAbsent(node, ignored -> new LinkedHashSet<>())
                        .add(demand.relation());
            }
        }
        Map<List<GraphRelation>, List<EntityRef>> nodesByMissingRelations =
                new LinkedHashMap<>();
        for (Map.Entry<EntityRef, LinkedHashSet<GraphRelation>> entry
                : outgoingByNode.entrySet()) {
            List<GraphRelation> relations = List.copyOf(entry.getValue());
            nodesByMissingRelations.computeIfAbsent(relations,
                    ignored -> new ArrayList<>()).add(entry.getKey());
        }
        for (Map.Entry<List<GraphRelation>, List<EntityRef>> group
                : nodesByMissingRelations.entrySet()) {
            acquireOutgoing(store, group.getValue(), group.getKey());
        }
    }

    /** Exposes statements as graph nodes so a following qualifier step retains the
     * exact statement GUID that made the route true. */
    private void acquireStatements(
            LocalGraphStore store, GraphAdjacencyDemand demand) throws Exception {
        String pid = statementProperty(demand.relation());
        requireOutgoing(demand, "statement");
        requireWikidataNodes(demand.nodes());
        if (api == null) throw new IllegalStateException(
                "No Wikidata action API is configured for statement graph edges");
        List<String> qids = demand.nodes().stream().map(EntityRef::id).toList();
        Map<String, List<WikidataApiClient.ApiStatement>> statements;
        try (GenerationLog.Group group = log.group(acquisitionTitle(demand))) {
            statements = api.getStatements(qids, pid, List.of(), group.batchSink(),
                    WikidataApiClient.StatementDetail.RANKED);
        }
        List<GraphEdge> edges = new ArrayList<>();
        for (EntityRef subject : demand.nodes()) {
            for (WikidataApiClient.ApiStatement statement
                    : statements.getOrDefault(subject.id(), List.of())) {
                EntityRef node = statementNode(pid, subject.id(), statement.id());
                edges.add(new GraphEdge(subject, demand.relation(), node, statement.id()));
                retainedStatements.put(statement.id(), statement);
            }
        }
        store.commitAdjacency(demand, edges, GraphAdjacencyCoverage.COMPLETE);
    }

    /** Reads one qualifier from the exact statement nodes reached by the previous
     * step. A statement the statement step already read is answered from that response;
     * the rest are grouped by their mainsnak property and their subjects fetched in
     * batches by the existing entity client. */
    private void acquireQualifiers(
            LocalGraphStore store, GraphAdjacencyDemand demand) throws Exception {
        String qualifier = qualifierProperty(demand.relation());
        requireOutgoing(demand, "qualifier");
        if (api == null) throw new IllegalStateException(
                "No Wikidata action API is configured for qualifier graph edges");
        Map<String, List<StatementNode>> byProperty = new LinkedHashMap<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (EntityRef node : demand.nodes()) {
            StatementNode parsed = parseStatementNode(node);
            WikidataApiClient.ApiStatement retained =
                    retainedStatements.get(parsed.statementId());
            if (retained != null) {
                qualifierEdges(parsed, retained, qualifier, demand.relation(), edges);
                continue;
            }
            byProperty.computeIfAbsent(parsed.property(), ignored -> new ArrayList<>())
                    .add(parsed);
        }
        for (Map.Entry<String, List<StatementNode>> group : byProperty.entrySet()) {
            List<String> subjects = group.getValue().stream().map(StatementNode::subject)
                    .distinct().toList();
            Map<String, List<WikidataApiClient.ApiStatement>> statements;
            try (GenerationLog.Group logGroup = log.group("Acquire " + qualifier
                    + " qualifiers from " + group.getValue().size() + " "
                    + group.getKey() + " statement node(s) — " + subjects.size()
                    + " subject(s), " + batches(subjects.size(), ENTITY_BATCH_SIZE)
                    + " initial request(s)")) {
                statements = api.getStatements(subjects, group.getKey(),
                        List.of(qualifier), logGroup.batchSink(),
                        WikidataApiClient.StatementDetail.RANKED);
            }
            Map<String, StatementNode> wanted = group.getValue().stream().collect(
                    java.util.stream.Collectors.toMap(StatementNode::statementId,
                            value -> value, (first, ignored) -> first, LinkedHashMap::new));
            for (Map.Entry<String, List<WikidataApiClient.ApiStatement>> subject
                    : statements.entrySet()) {
                for (WikidataApiClient.ApiStatement statement : subject.getValue()) {
                    StatementNode origin = wanted.get(statement.id());
                    if (origin == null) continue;
                    retainedStatements.put(statement.id(), statement);
                    qualifierEdges(origin, statement, qualifier, demand.relation(), edges);
                }
            }
        }
        store.commitAdjacency(demand, edges, GraphAdjacencyCoverage.COMPLETE);
    }

    /**
     * The requests each step of {@code route} makes, in the batch sizes this acquisition
     * uses, so a route can be judged before it runs. The number of entities a later step
     * starts from is known only once the earlier step has run.
     */
    public static List<String> requestEstimate(datasource.graph.RelationRoute route) {
        List<String> lines = new ArrayList<>();
        datasource.wikidata.WikidataRouteRelation previous = null;
        for (int index = 0; index < route.steps().size(); index++) {
            datasource.graph.RelationRoute.Step step = route.steps().get(index);
            datasource.wikidata.WikidataRouteRelation relation =
                    datasource.wikidata.WikidataRouteRelation.parse(step.relation());
            String from = index == 0 ? "owners"
                    : "entities reached by step " + index;
            String requests;
            if (relation == null) {
                requests = "not a Wikidata relation";
            } else if (relation.kind() == datasource.wikidata.WikidataRouteRelation.Kind.QUALIFIER
                    && previous != null && previous.kind()
                    == datasource.wikidata.WikidataRouteRelation.Kind.STATEMENT) {
                requests = "no request: read from the statements step " + index + " fetched";
            } else if (relation.kind() == datasource.wikidata.WikidataRouteRelation.Kind.PROPERTY
                    && step.direction() == GraphTraversalDirection.INCOMING) {
                requests = "1 SPARQL query per " + REVERSE_BATCH_SIZE + " " + from;
            } else {
                requests = "1 Wikidata entity request per " + ENTITY_BATCH_SIZE + " " + from;
            }
            lines.add("Step " + (index + 1) + " — "
                    + (step.direction() == GraphTraversalDirection.INCOMING
                            ? "incoming " : "outgoing ")
                    + (relation == null ? step.relation().relationId()
                            : relation.kind() + " " + relation.pid())
                    + ": " + requests);
            previous = relation;
        }
        return List.copyOf(lines);
    }

    private static void qualifierEdges(StatementNode origin,
            WikidataApiClient.ApiStatement statement, String qualifier,
            GraphRelation relation, List<GraphEdge> edges) {
        for (String value : statement.qualifier(qualifier)) {
            if (!WikidataIds.isQid(value)) continue;
            edges.add(new GraphEdge(origin.ref(), relation,
                    EntityRef.wikidata(value), statement.id()));
        }
    }

    private void acquireOutgoing(
            LocalGraphStore store, GraphAdjacencyDemand demand) throws Exception {
        acquireOutgoing(store, demand.nodes(), List.of(demand.relation()));
    }

    private void acquireOutgoing(
            LocalGraphStore store,
            List<EntityRef> requested,
            List<GraphRelation> relations) throws Exception {
        if (api == null) throw new IllegalStateException(
                "No Wikidata action API is configured for outgoing graph edges");
        List<String> qids = requested.stream().map(EntityRef::id).toList();
        List<String> pids = relations.stream().map(GraphRelation::relationId).toList();
        String source = "graph adjacency";
        api.facts().recordRetentionPlan(source, qids, pids);
        api.facts().recordDemand(source, qids, pids);
        WikidataApiClient.PartialStatements loaded;
        try (GenerationLog.Group group = log.group(acquisitionTitle(
                requested, relations, GraphTraversalDirection.OUTGOING))) {
            loaded = api.getStatementsByPropertyPartial(qids, pids, group.batchSink(),
                    (completedQids, statements) -> {
                        List<EntityRef> completedNodes = completedQids.stream()
                                .map(EntityRef::wikidata).toList();
                        for (GraphRelation relation : relations) {
                            GraphAdjacencyDemand completed = new GraphAdjacencyDemand(
                                    completedNodes, relation,
                                    GraphTraversalDirection.OUTGOING);
                            store.commitAdjacency(completed, edges(relation, statements),
                                    GraphAdjacencyCoverage.COMPLETE);
                        }
                    });
        }
        Set<String> unavailable = new LinkedHashSet<>(loaded.unavailableQids());
        for (EntityRef node : requested) {
            if (unavailable.contains(node.id())) {
                for (GraphRelation relation : relations) {
                    store.markCoverage(new GraphAdjacencyDemand(
                                    List.of(node), relation,
                                    GraphTraversalDirection.OUTGOING),
                            GraphAdjacencyCoverage.UNAVAILABLE);
                }
            }
        }
    }

    private void acquireIncoming(
            LocalGraphStore store, GraphAdjacencyDemand demand) throws Exception {
        if (sparql == null) throw new IllegalStateException(
                "No Wikidata query service is configured for incoming graph edges");
        List<WorkUnit<List<GraphEdge>>> units = new ArrayList<>();
        for (int from = 0; from < demand.nodes().size(); from += REVERSE_BATCH_SIZE) {
            units.add(new IncomingUnit(sparql, demand.relation(), demand.nodes().subList(
                    from, Math.min(demand.nodes().size(), from + REVERSE_BATCH_SIZE))));
        }
        List<WorkDescriptor> failed;
        try (GenerationLog.Group group = log.group(acquisitionTitle(demand))) {
            failed = new BatchExecutor<List<GraphEdge>>(
                    BatchPolicy.defaults().withResume(false), group.batchProgress(),
                    WikidataBatchFailureClassifier.INSTANCE, cancellation,
                    BatchCheckpointStore.NONE)
                    .runBestEffort(units, (descriptor, edges) -> {
                        List<EntityRef> completed = objectsOf(descriptor);
                        store.commitAdjacency(new GraphAdjacencyDemand(completed,
                                        demand.relation(), GraphTraversalDirection.INCOMING),
                                edges, GraphAdjacencyCoverage.INCOMPLETE);
                    });
        }
        Set<EntityRef> unavailable = new LinkedHashSet<>();
        for (WorkDescriptor descriptor : failed) unavailable.addAll(objectsOf(descriptor));
        for (EntityRef node : demand.nodes()) {
            // This unpaged WDQS query can return a syntactically valid partial 200.
            // Retain every returned edge, but never turn absence from that answer into
            // proof of completeness. Keyset paging is required before COMPLETE is safe.
            if (unavailable.contains(node)) {
                store.markCoverage(single(node, demand),
                        GraphAdjacencyCoverage.UNAVAILABLE);
            }
        }
    }

    private static List<GraphEdge> edges(
            GraphRelation relation,
            Map<String, Map<String, List<WikidataApiClient.ApiStatement>>> statements) {
        List<GraphEdge> edges = new ArrayList<>();
        Map<String, List<WikidataApiClient.ApiStatement>> byEntity =
                statements.getOrDefault(relation.relationId(), Map.of());
        for (Map.Entry<String, List<WikidataApiClient.ApiStatement>> entry
                : byEntity.entrySet()) {
            EntityRef entity = EntityRef.wikidata(entry.getKey());
            for (WikidataApiClient.ApiStatement statement : entry.getValue()) {
                edges.add(new GraphEdge(entity, relation,
                        value(statement), statement.id()));
            }
        }
        return edges;
    }

    /** The nodes one unit asked about, read back from the descriptor it reports with.
     *  After a split the halves report their own, which is what makes per-node coverage
     *  follow what actually ran. */
    private static List<EntityRef> objectsOf(WorkDescriptor descriptor) {
        return java.util.Arrays.stream(
                        descriptor.parameters().getOrDefault("objects", "").split(","))
                .filter(WikidataIds::isQid)
                .map(EntityRef::wikidata)
                .toList();
    }

    private static GraphAdjacencyDemand single(
            EntityRef node, GraphAdjacencyDemand demand) {
        return new GraphAdjacencyDemand(
                List.of(node), demand.relation(), demand.direction());
    }

    static String acquisitionTitle(GraphAdjacencyDemand demand) {
        return acquisitionTitle(demand.nodes(), List.of(demand.relation()),
                demand.direction());
    }

    private static String acquisitionTitle(
            List<EntityRef> nodes,
            List<GraphRelation> relations,
            GraphTraversalDirection direction) {
        int batchSize = direction == GraphTraversalDirection.INCOMING
                ? REVERSE_BATCH_SIZE : ENTITY_BATCH_SIZE;
        int initialRequests = batches(nodes.size(), batchSize);
        String properties = relations.stream().map(GraphRelation::relationId)
                .collect(java.util.stream.Collectors.joining(" + "));
        return "Acquire " + properties + " "
                + (direction == GraphTraversalDirection.INCOMING ? "in" : "out")
                + " — " + nodes.size() + " node(s), " + initialRequests
                + " initial request(s)"
                + (initialRequests == 0 ? "" : "; adaptive splits may add requests");
    }

    private static int batches(int values, int size) {
        return values <= 0 ? 0 : (values + size - 1) / size;
    }

    private static GraphValue value(WikidataApiClient.ApiStatement statement) {
        return "wikibase-entityid".equals(statement.valueType())
                && WikidataIds.isQid(statement.value())
                ? EntityRef.wikidata(statement.value())
                : new LiteralValue(statement.valueType(), statement.value());
    }

    /** The whole plan's relations, checked before the first request is spent — see
     *  {@link GraphAdjacencyAcquirer#validateRelations}. */
    @Override public void validateRelations(Collection<GraphRelation> relations) {
        if (relations == null) return;
        for (GraphRelation relation : relations) requireWikidataRelation(relation);
    }

    private static void requireWikidataRelation(GraphRelation relation) {
        if (datasource.wikidata.WikidataRouteRelation.parse(relation) == null) {
            throw new IllegalArgumentException(
                    "Wikidata graph acquisition requires a property, statement:PID or "
                            + "qualifier:PID relation: "
                            + relation);
        }
    }

    private static void requireWikidata(
            GraphRelation relation, Collection<EntityRef> nodes) {
        requireWikidataRelation(relation);
        for (EntityRef node : nodes) {
            if (node == null || !EntityRef.WIKIDATA.equals(node.namespace())
                    || !WikidataIds.isQid(node.id())) {
                throw new IllegalArgumentException(
                        "Wikidata graph acquisition requires Wikidata QIDs: " + node);
            }
        }
    }

    private static void requireWikidataNodes(Collection<EntityRef> nodes) {
        for (EntityRef node : nodes) {
            if (node == null || !EntityRef.WIKIDATA.equals(node.namespace())
                    || !WikidataIds.isQid(node.id())) {
                throw new IllegalArgumentException(
                        "Wikidata statement acquisition requires Wikidata QIDs: " + node);
            }
        }
    }

    private static void requireOutgoing(GraphAdjacencyDemand demand, String kind) {
        if (demand.direction() != GraphTraversalDirection.OUTGOING) {
            throw new IllegalArgumentException(
                    "Wikidata " + kind + " relations support outgoing traversal only");
        }
    }

    private static String statementProperty(GraphRelation relation) {
        return routePid(relation, datasource.wikidata.WikidataRouteRelation.Kind.STATEMENT);
    }

    private static String qualifierProperty(GraphRelation relation) {
        return routePid(relation, datasource.wikidata.WikidataRouteRelation.Kind.QUALIFIER);
    }

    private static String routePid(GraphRelation relation,
            datasource.wikidata.WikidataRouteRelation.Kind kind) {
        datasource.wikidata.WikidataRouteRelation step =
                datasource.wikidata.WikidataRouteRelation.parse(relation);
        return step != null && step.kind() == kind ? step.pid() : null;
    }

    private static EntityRef statementNode(String property, String subject, String id) {
        return new EntityRef(STATEMENT_NAMESPACE,
                property + "|" + subject + "|" + id);
    }

    private static StatementNode parseStatementNode(EntityRef node) {
        if (node == null || !STATEMENT_NAMESPACE.equals(node.namespace())) {
            throw new IllegalArgumentException(
                    "Wikidata qualifier acquisition requires statement nodes: " + node);
        }
        String[] parts = node.id().split("\\|", 3);
        if (parts.length != 3 || !WikidataIds.isPid(parts[0])
                || !WikidataIds.isQid(parts[1]) || parts[2].isBlank()) {
            throw new IllegalArgumentException("Invalid Wikidata statement node: " + node);
        }
        return new StatementNode(parts[0], parts[1], parts[2], node);
    }

    private record StatementNode(
            String property, String subject, String statementId, EntityRef ref) { }

    /** Truthy incoming edges, each with the statement that states it, so an edge
     *  reached this way carries the same statement id as an outgoing one. */
    static String incomingQuery(GraphRelation relation, List<EntityRef> objects) {
        String pid = relation.relationId();
        StringBuilder query = new StringBuilder(
                "SELECT DISTINCT ?subject ?value ?statement WHERE {\n  VALUES ?value {");
        for (EntityRef object : objects) query.append(" wd:").append(object.id());
        return query.append(" }\n  ?subject wdt:").append(pid).append(" ?value .\n")
                .append("  ?subject p:").append(pid).append(" ?statement .\n")
                .append("  ?statement ps:").append(pid).append(" ?value .\n}")
                .toString();
    }

    /** A WDQS statement IRI ends in the GUID with its first "$" written as "-". */
    static String statementGuid(String iri) {
        if (iri == null || iri.isBlank()) return "";
        String tail = iri.substring(iri.lastIndexOf('/') + 1);
        int dash = tail.indexOf('-');
        if (dash <= 0 || !WikidataIds.isQid(tail.substring(0, dash))) return "";
        return tail.substring(0, dash) + "$" + tail.substring(dash + 1);
    }

    private static final class IncomingUnit implements WorkUnit<List<GraphEdge>> {
        private final WikidataSparqlClient client;
        private final GraphRelation relation;
        private final List<EntityRef> objects;
        private final WorkDescriptor descriptor;

        private IncomingUnit(
                WikidataSparqlClient client, GraphRelation relation,
                List<EntityRef> objects) {
            this.client = client;
            this.relation = relation;
            this.objects = List.copyOf(objects);
            String ids = this.objects.stream().map(EntityRef::id)
                    .collect(java.util.stream.Collectors.joining(","));
            descriptor = new WorkDescriptor("graph-incoming", relation.relationId()
                    + ":" + ids, relation.relationId() + " incoming graph edges for "
                    + objects.size() + " object(s)",
                    Map.of("property", relation.relationId(), "objects", ids));
        }

        @Override public WorkDescriptor descriptor() { return descriptor; }
        @Override public String request() { return incomingQuery(relation, objects); }
        @Override public List<GraphEdge> execute() throws Exception {
            Map<String, GraphEdge> edges = new LinkedHashMap<>();
            for (WikidataBinding binding : client.query(request())) {
                String subject = binding.qid("subject");
                String value = binding.qid("value");
                if (!WikidataIds.isQid(subject) || !WikidataIds.isQid(value)) continue;
                GraphEdge edge = new GraphEdge(EntityRef.wikidata(subject), relation,
                        EntityRef.wikidata(value),
                        statementGuid(binding.value("statement")));
                edges.putIfAbsent(subject + "\u0000" + value, edge);
            }
            return List.copyOf(edges.values());
        }
        @Override public List<? extends WorkUnit<List<GraphEdge>>> split() {
            if (objects.size() < 2) return List.of();
            int middle = objects.size() / 2;
            return List.of(new IncomingUnit(client, relation, objects.subList(0, middle)),
                    new IncomingUnit(client, relation, objects.subList(middle, objects.size())));
        }
    }
}
