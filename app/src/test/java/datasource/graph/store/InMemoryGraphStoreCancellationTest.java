package datasource.graph.store;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** A confirmed Cancel must stop expensive local graph work, not only network work. */
class InMemoryGraphStoreCancellationTest {
    @Test void anInterruptedLocalAdjacencyScanStopsImmediately() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphRelation relation = new GraphRelation("provider", "relation");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                List.of(new EntityRef("provider", "entity")), relation,
                GraphTraversalDirection.OUTGOING);

        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> store.adjacent(demand));
        } finally {
            Thread.interrupted();
        }
    }
}
