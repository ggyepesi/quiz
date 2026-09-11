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

    private void acquireOutgoing(
            LocalGraphStore store, GraphAdjacencyDemand demand) throws Exception {
        if (api == null) throw new IllegalStateException(
                "No Wikidata action API is configured for outgoing graph edges");
        List<EntityRef> requested = demand.nodes();
        List<String> qids = requested.stream().map(EntityRef::id).toList();
        List<String> pids = List.of(demand.relation().relationId());
        String source = "graph adjacency";
        api.facts().recordRetentionPlan(source, qids, pids);
        api.facts().recordDemand(source, qids, pids);
        WikidataApiClient.PartialStatements loaded = api.getStatementsByPropertyPartial(
                qids, pids, log.batchSink());
        Set<String> unavailable = new LinkedHashSet<>(loaded.unavailableQids());
        List<GraphEdge> edges = new ArrayList<>();
        Map<String, List<WikidataApiClient.ApiStatement>> byEntity =
                loaded.statements().getOrDefault(demand.relation().relationId(), Map.of());
        for (Map.Entry<String, List<WikidataApiClient.ApiStatement>> entry
                : byEntity.entrySet()) {
            EntityRef entity = EntityRef.wikidata(entry.getKey());
            for (WikidataApiClient.ApiStatement statement : entry.getValue()) {
                edges.add(new GraphEdge(entity, demand.relation(),
                        value(statement), statement.id()));
            }
        }
        store.addEdges(edges);
        for (EntityRef node : requested) {
            store.markCoverage(new GraphAdjacencyDemand(List.of(node), demand.relation(),
                            GraphTraversalDirection.OUTGOING),
                    unavailable.contains(node.id())
                            ? GraphAdjacencyCoverage.UNAVAILABLE
                            : GraphAdjacencyCoverage.COMPLETE);
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
        List<WorkDescriptor> failed = new BatchExecutor<List<GraphEdge>>(
                BatchPolicy.defaults().withResume(false), log.batchProgress(),
                WikidataBatchFailureClassifier.INSTANCE, cancellation,
                BatchCheckpointStore.NONE)
                .runBestEffort(units, (descriptor, edges) -> {
                    store.addEdges(edges);
                });
        Set<EntityRef> unavailable = new LinkedHashSet<>();
        for (WorkDescriptor descriptor : failed) unavailable.addAll(objectsOf(descriptor));
        for (EntityRef node : demand.nodes()) {
            // This unpaged WDQS query can return a syntactically valid partial 200.
            // Retain every returned edge, but never turn absence from that answer into
            // proof of completeness. Keyset paging is required before COMPLETE is safe.
            GraphAdjacencyCoverage coverage = unavailable.contains(node)
                    ? GraphAdjacencyCoverage.UNAVAILABLE
                    : GraphAdjacencyCoverage.INCOMPLETE;
            store.markCoverage(single(node, demand), coverage);
        }
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
