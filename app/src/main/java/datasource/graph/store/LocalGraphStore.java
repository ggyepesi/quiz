package datasource.graph.store;

import datasource.EntityRef;

import java.util.Collection;

/** Provider-neutral accumulated graph and adjacency-coverage boundary. */
public interface LocalGraphStore extends AutoCloseable {
    void addEdges(Collection<GraphEdge> edges);
    void markCoverage(GraphAdjacencyDemand demand, GraphAdjacencyCoverage coverage);

    /**
     * Publishes one completely acquired partition. Durable stores override this so the
     * edges and the knowledge that every requested node was answered become one commit.
     * A batch checkpoint without this result commit is not resumable: it would remember
     * that the request finished while losing what the request returned.
     */
    default void commitAdjacency(
            GraphAdjacencyDemand demand,
            Collection<GraphEdge> edges,
            GraphAdjacencyCoverage coverage) {
        addEdges(edges);
        markCoverage(demand, coverage);
    }

    GraphAdjacencyCoverage adjacencyKnowledge(
            EntityRef node, GraphAdjacencyDemand demand);
    GraphAdjacencyResult adjacent(GraphAdjacencyDemand demand);
    @Override default void close() { }
}
