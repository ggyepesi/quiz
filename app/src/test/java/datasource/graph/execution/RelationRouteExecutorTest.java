package datasource.graph.execution;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.RelationRoute;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.GraphEdge;
import datasource.graph.store.InMemoryGraphStore;
import datasource.graph.store.LocalGraphStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelationRouteExecutorTest {
    private static final GraphRelation CHILD = relation("P40");
    private static final GraphRelation FATHER = relation("P22");
    private static final GraphRelation STATEMENT = relation("statement:P40");
    private static final GraphRelation ROLE = relation("qualifier:P3831");
    private static final GraphRelation MEMBER = relation("P527");

    @Test
    void unionsDirectInverseAndQualifiedGroupRoutesPerOwner() throws Exception {
        EntityRef owner = q("Q101");
        EntityRef directChild = q("Q201");
        EntityRef inverseChild = q("Q202");
        EntityRef statement = new EntityRef("wikidata-statement", "Q101$P40-1");
        EntityRef group = q("Q301");
        EntityRef firstMember = q("Q203");
        EntityRef secondMember = q("Q204");

        InMemoryGraphStore store = new InMemoryGraphStore();
        store.addEdges(List.of(
                edge(owner, CHILD, directChild, "Q101$direct"),
                edge(inverseChild, FATHER, owner, "Q202$father"),
                edge(owner, STATEMENT, statement, "Q101$P40-1"),
                edge(statement, ROLE, group, "Q101$P40-1"),
                edge(group, MEMBER, firstMember, "Q301$member-1"),
                edge(group, MEMBER, secondMember, "Q301$member-2")));
        markComplete(store, owner, CHILD, GraphTraversalDirection.OUTGOING);
        markComplete(store, owner, FATHER, GraphTraversalDirection.INCOMING);
        markComplete(store, owner, STATEMENT, GraphTraversalDirection.OUTGOING);
        markComplete(store, statement, ROLE, GraphTraversalDirection.OUTGOING);
        markComplete(store, group, MEMBER, GraphTraversalDirection.OUTGOING);

        RelationRoute direct = route("direct", step(CHILD, GraphTraversalDirection.OUTGOING));
        RelationRoute inverse = route("inverse", step(FATHER, GraphTraversalDirection.INCOMING));
        RelationRoute qualifiedGroup = route("qualified-group",
                step(STATEMENT, GraphTraversalDirection.OUTGOING),
                step(ROLE, GraphTraversalDirection.OUTGOING),
                step(MEMBER, GraphTraversalDirection.OUTGOING));

        RelationRouteExecutor.Result result = RelationRouteExecutor.execute(
                store, List.of(direct, inverse, qualifiedGroup), List.of(owner),
                (ignoredStore, ignoredDemand) -> { });

        assertEquals(List.of(directChild, inverseChild, firstMember, secondMember),
                result.terminals(owner));
        assertEquals(List.of("direct", "inverse", "qualified-group", "qualified-group"),
                result.matches(owner).stream().map(match -> match.route().id()).toList());
        assertEquals(List.of("Q101$P40-1", "Q101$P40-1", "Q301$member-1"),
                result.matches(owner).get(2).path().stream()
                        .map(GraphEdge::provenanceId).toList());
    }

    @Test
    void batchesEachHopAcrossAllOwnersAndAcquiresOnlyMissingAdjacency() throws Exception {
        EntityRef firstOwner = q("Q101");
        EntityRef secondOwner = q("Q102");
        EntityRef firstGroup = q("Q301");
        EntityRef secondGroup = q("Q302");
        EntityRef firstMember = q("Q201");
        EntityRef secondMember = q("Q202");
        InMemoryGraphStore store = new InMemoryGraphStore();
        List<GraphAdjacencyDemand> acquired = new ArrayList<>();
        GraphAdjacencyAcquirer acquirer = (local, demand) -> {
            acquired.add(demand);
            if (demand.relation().equals(CHILD)) {
                local.commitAdjacency(demand, List.of(
                        edge(firstOwner, CHILD, firstGroup, "first"),
                        edge(secondOwner, CHILD, secondGroup, "second")),
                        GraphAdjacencyCoverage.COMPLETE);
            } else {
                local.commitAdjacency(demand, List.of(
                        edge(firstGroup, MEMBER, firstMember, "first-member"),
                        edge(secondGroup, MEMBER, secondMember, "second-member")),
                        GraphAdjacencyCoverage.COMPLETE);
            }
        };

        RelationRouteExecutor.Result result = RelationRouteExecutor.execute(store,
                List.of(route("two-hops",
                        step(CHILD, GraphTraversalDirection.OUTGOING),
                        step(MEMBER, GraphTraversalDirection.OUTGOING))),
                List.of(firstOwner, secondOwner), acquirer);

        assertEquals(2, acquired.size());
        assertEquals(List.of(firstOwner, secondOwner), acquired.get(0).nodes());
        assertEquals(List.of(firstGroup, secondGroup), acquired.get(1).nodes());
        assertEquals(List.of(firstMember), result.terminals(firstOwner));
        assertEquals(List.of(secondMember), result.terminals(secondOwner));
    }

    private static RelationRoute route(String id, RelationRoute.Step... steps) {
        return new RelationRoute(id, id, List.of(steps));
    }

    private static RelationRoute.Step step(
            GraphRelation relation, GraphTraversalDirection direction) {
        return new RelationRoute.Step(relation, direction);
    }

    private static GraphRelation relation(String id) {
        return new GraphRelation("wikidata", id);
    }

    private static EntityRef q(String qid) { return EntityRef.wikidata(qid); }

    private static GraphEdge edge(EntityRef source, GraphRelation relation,
            EntityRef target, String provenance) {
        return new GraphEdge(source, relation, target, provenance);
    }

    private static void markComplete(LocalGraphStore store, EntityRef node,
            GraphRelation relation, GraphTraversalDirection direction) {
        store.markCoverage(new GraphAdjacencyDemand(List.of(node), relation, direction),
                GraphAdjacencyCoverage.COMPLETE);
    }
}
