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
    private static final int ENTITY_BATCH_SIZE = 50;
    private static final int REVERSE_BATCH_SIZE = 50;

    private final WikidataApiClient api;
    private final WikidataSparqlClient sparql;
    private final GenerationLog log;
    private final CancellationToken cancellation;

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
            requireWikidata(demand.relation(), demand.nodes());
        }
        Map<EntityRef, LinkedHashSet<GraphRelation>> outgoingByNode =
                new LinkedHashMap<>();
        for (GraphAdjacencyDemand demand : requested) {
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
        if (relation == null || !PROVIDER.equals(relation.providerId())
                || !WikidataIds.isPid(relation.relationId())) {
            throw new IllegalArgumentException(
                    "Wikidata graph acquisition requires a Wikidata property relation: "
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

    static String incomingQuery(GraphRelation relation, List<EntityRef> objects) {
        StringBuilder query = new StringBuilder(
                "SELECT DISTINCT ?subject ?value WHERE {\n  VALUES ?value {");
        for (EntityRef object : objects) query.append(" wd:").append(object.id());
        return query.append(" }\n  ?subject wdt:")
                .append(relation.relationId()).append(" ?value .\n}")
                .toString();
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
                        EntityRef.wikidata(value), "");
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
