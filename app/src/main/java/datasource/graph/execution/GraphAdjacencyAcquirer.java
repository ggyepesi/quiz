package datasource.graph.execution;

import datasource.graph.GraphRelation;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.LocalGraphStore;

import java.util.Collection;

/** Supplies one missing adjacency demand to the same store a graph wave reads. */
@FunctionalInterface
public interface GraphAdjacencyAcquirer {
    void acquire(LocalGraphStore store, GraphAdjacencyDemand demand) throws Exception;

    /**
     * Rejects every relation this acquirer could not fetch, before any of them is
     * asked for.
     *
     * <p>Validating each demand as it arrives is not the same rule. A relation deep in
     * the plan is then reached only after the earlier hops have been fetched, paid for
     * and retained — and if an earlier hop happens to return nothing, the bad relation
     * arrives with an empty node list and is never examined at all, so an unrunnable
     * graph finishes quietly with everything in Review. Which relation is acquirable is
     * the provider's question, so the neutral executor asks it here rather than
     * deciding; an acquirer that can fetch anything overrides nothing.
     */
    default void validateRelations(Collection<GraphRelation> relations) { }
}
