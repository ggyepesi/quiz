package datasource.graph.store;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Adjacency is indexed by its authored relation and endpoint, not rediscovered by
 * scanning every retained edge for every classified entity. */
class InMemoryGraphStoreIndexTest {
    @Test void outgoingAndIncomingLookupsUseTheSameIndexedEdges() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphRelation office = new GraphRelation("provider", "office");
        GraphRelation unrelated = new GraphRelation("provider", "unrelated");
        EntityRef holder = new EntityRef("provider", "holder");
        EntityRef position = new EntityRef("provider", "position");
        GraphEdge edge = new GraphEdge(holder, office, position, "statement");
        store.addEdges(List.of(
                new GraphEdge(new EntityRef("provider", "other"), unrelated,
                        new EntityRef("provider", "other-target"), "other-statement"),
                edge, edge));

        assertEquals(List.of(edge), store.adjacent(demand(
                holder, office, GraphTraversalDirection.OUTGOING)).edges());
        assertEquals(List.of(edge), store.adjacent(demand(
                position, office, GraphTraversalDirection.INCOMING)).edges());
    }

    private static GraphAdjacencyDemand demand(
            EntityRef node, GraphRelation relation, GraphTraversalDirection direction) {
        return new GraphAdjacencyDemand(List.of(node), relation, direction);
    }
}
