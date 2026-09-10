package wikidata.explore.generation;

import datasource.EntityRef;
import datasource.GraphValue;
import datasource.LiteralValue;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.GraphEvidenceCondition;
import datasource.graph.constraint.GraphEvidenceConditionResult;
import datasource.graph.constraint.GraphEvidenceConditions;
import datasource.graph.constraint.GraphNodeCondition;
import datasource.graph.constraint.GraphPath;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.GraphEdge;
import datasource.graph.store.LocalGraphStore;
import wikidata.WikidataIds;
import wikidata.api.WikidataApiClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Acquires both bounded Wikidata adjacency phases required by one evidence condition,
 * then classifies the supplied population from the retained local facts.
 *
 * <p>The action API answers outgoing claims by known QID in batches of 50. Incoming
 * adjacency needs a different provider operation and is rejected explicitly in this
 * first slice rather than being mistaken for outgoing data.
 */
public final class WikidataGraphEvidenceAcquisition {
    private static final String PROVIDER = "wikidata";

    private WikidataGraphEvidenceAcquisition() { }

    public record Result(
            List<GraphEvidenceConditionResult> classifications,
            int evidenceFailedBatches,
            int testFailedBatches) {
        public Result {
            classifications = classifications == null
                    ? List.of() : List.copyOf(classifications);
        }

        public long accepted() {
            return count(GraphEvidenceConditionResult.Decision.ACCEPTED);
        }

        public long rejected() {
            return count(GraphEvidenceConditionResult.Decision.REJECTED);
        }

        public long review() {
            return count(GraphEvidenceConditionResult.Decision.REVIEW);
        }

        private long count(GraphEvidenceConditionResult.Decision decision) {
            return classifications.stream()
                    .filter(result -> result.decision() == decision).count();
        }
    }

    public static Result acquire(
            WikidataApiClient api,
            LocalGraphStore store,
            GraphEvidenceCondition condition,
            Collection<EntityRef> population,
            WikidataApiClient.BatchLog batchLog) throws Exception {
        if (api == null || store == null || condition == null) {
            throw new IllegalArgumentException(
                    "API, graph store and evidence condition are required");
        }
        List<EntityRef> members = wikidataNodes(population);

        // The complete provider plan is validated before the first request: an
        // unsupported second hop must not be discovered after first-hop acquisition
        // has mutated the stores and spent network work. Checked in a loop rather than
        // in a stream's peek, whose javadoc reserves it for debugging and permits an
        // implementation to elide it — a guarantee this specific must not rest on
        // which terminal operation happens to follow.
        for (GraphPath path : condition.evidencePaths()) {
            requireOutgoing(path.relation(), path.direction());
        }
        for (GraphNodeCondition test : condition.tests()) {
            requireOutgoing(test.relation(), test.direction());
        }
        List<GraphRelation> evidenceRelations = condition.evidencePaths().stream()
                .map(GraphPath::relation).distinct().toList();
        List<GraphRelation> testRelations = condition.tests().stream()
                .map(GraphNodeCondition::relation).distinct().toList();

        Phase evidence = acquire(api, store, members, evidenceRelations,
                "graph evidence " + condition.name() + " first hop", batchLog);

        List<EntityRef> reached = reachedEntities(store, condition, members);
        Phase tests = acquire(api, store, reached, testRelations,
                "graph evidence " + condition.name() + " tests", batchLog);

        List<GraphEvidenceConditionResult> classified = members.stream()
                .map(node -> GraphEvidenceConditions.evaluate(store, condition, node))
                .toList();
        return new Result(classified, evidence.failedBatches(), tests.failedBatches());
    }

    private static Phase acquire(
            WikidataApiClient api,
            LocalGraphStore store,
            List<EntityRef> nodes,
            List<GraphRelation> relations,
            String demandSource,
            WikidataApiClient.BatchLog batchLog) throws Exception {
        if (nodes.isEmpty() || relations.isEmpty()) return new Phase(0);

        List<EntityRef> unresolved = nodes.stream().filter(node -> relations.stream()
                .anyMatch(relation -> store.adjacencyKnowledge(node,
                        demand(node, relation)) != GraphAdjacencyCoverage.COMPLETE))
                .toList();
        if (unresolved.isEmpty()) return new Phase(0);

        List<String> qids = unresolved.stream().map(EntityRef::id).toList();
        List<String> pids = relations.stream().map(GraphRelation::relationId).toList();
        api.facts().recordRetentionPlan(demandSource, qids, pids);
        api.facts().recordDemand(demandSource, qids, pids);
        WikidataApiClient.PartialStatements loaded =
                api.getStatementsByPropertyPartial(qids, pids, batchLog);
        Set<String> unavailable = new LinkedHashSet<>(loaded.unavailableQids());

        List<GraphEdge> edges = new ArrayList<>();
        for (GraphRelation relation : relations) {
            Map<String, List<WikidataApiClient.ApiStatement>> byEntity =
                    loaded.statements().getOrDefault(
                            relation.relationId(), Map.of());
            for (Map.Entry<String, List<WikidataApiClient.ApiStatement>> entry
                    : byEntity.entrySet()) {
                EntityRef source = EntityRef.wikidata(entry.getKey());
                for (WikidataApiClient.ApiStatement statement : entry.getValue()) {
                    edges.add(new GraphEdge(source, relation,
                            value(statement), statement.id()));
                }
            }
            for (EntityRef node : unresolved) {
                store.markCoverage(demand(node, relation),
                        unavailable.contains(node.id())
                                ? GraphAdjacencyCoverage.UNAVAILABLE
                                : GraphAdjacencyCoverage.COMPLETE);
            }
        }
        store.addEdges(edges);
        return new Phase(loaded.failedBatches());
    }

    private static GraphValue value(WikidataApiClient.ApiStatement statement) {
        return "wikibase-entityid".equals(statement.valueType())
                && WikidataIds.isQid(statement.value())
                ? EntityRef.wikidata(statement.value())
                : new LiteralValue(statement.valueType(), statement.value());
    }

    private static List<EntityRef> reachedEntities(
            LocalGraphStore store,
            GraphEvidenceCondition condition,
            List<EntityRef> members) {
        Set<EntityRef> reached = new LinkedHashSet<>();
        for (var path : condition.evidencePaths()) {
            var adjacent = store.adjacent(new GraphAdjacencyDemand(
                    members, path.relation(), path.direction()));
            for (GraphEdge edge : adjacent.edges()) {
                EntityRef entity = edge.entityEndpoint(path.direction());
                if (entity != null) reached.add(entity);
            }
        }
        return List.copyOf(reached);
    }

    private static List<EntityRef> wikidataNodes(Collection<EntityRef> nodes) {
        if (nodes == null) return List.of();
        Map<String, EntityRef> unique = new LinkedHashMap<>();
        for (EntityRef node : nodes) {
            if (node == null) continue;
            if (!EntityRef.WIKIDATA.equals(node.namespace())
                    || !WikidataIds.isQid(node.id())) {
                throw new IllegalArgumentException(
                        "Wikidata evidence acquisition requires Wikidata QIDs: " + node);
            }
            unique.putIfAbsent(node.id(), node);
        }
        return List.copyOf(unique.values());
    }

    private static void requireOutgoing(
            GraphRelation relation, GraphTraversalDirection direction) {
        if (!PROVIDER.equals(relation.providerId())
                || !WikidataIds.isPid(relation.relationId())) {
            throw new IllegalArgumentException(
                    "Wikidata evidence acquisition requires Wikidata property relations: "
                            + relation);
        }
        if (direction != GraphTraversalDirection.OUTGOING) {
            throw new IllegalArgumentException(
                    "Wikidata action-API evidence acquisition supports outgoing relations only: "
                            + relation.relationId());
        }
    }

    private static GraphAdjacencyDemand demand(
            EntityRef node, GraphRelation relation) {
        return new GraphAdjacencyDemand(
                List.of(node), relation, GraphTraversalDirection.OUTGOING);
    }

    private record Phase(int failedBatches) { }
}
