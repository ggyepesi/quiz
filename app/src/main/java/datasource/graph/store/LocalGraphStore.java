package datasource.graph.store;

import datasource.EntityRef;

import java.util.Collection;

/** Provider-neutral accumulated graph and adjacency-coverage boundary. */
public interface LocalGraphStore extends AutoCloseable {
    void addEdges(Collection<GraphEdge> edges);
    void markCoverage(GraphAdjacencyDemand demand, GraphAdjacencyCoverage coverage);
    GraphAdjacencyCoverage adjacencyKnowledge(
            EntityRef node, GraphAdjacencyDemand demand);
    GraphAdjacencyResult adjacent(GraphAdjacencyDemand demand);
    @Override default void close() { }
}
