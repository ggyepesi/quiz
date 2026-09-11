package datasource.graph.execution;

import datasource.EntityRef;
import datasource.LiteralValue;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.GraphEvidenceCondition;
import datasource.graph.constraint.GraphPath;
import datasource.graph.constraint.GraphRelationExists;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphEdge;
import datasource.graph.store.InMemoryGraphStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The authored graph acquires, classifies and advances through one audited path. */
class GraphDiscoveryExecutorTest {
    private static final GraphRelation SUBCLASS = relation("P279");
    private static final GraphRelation JURISDICTION = relation("P1001");
    private static final GraphRelation DISSOLVED = relation("P576");
    private static final GraphRelation SUCCESSOR = relation("P155");

    @Test void aReachedNodeIsClassifiedThroughItsEvidenceEntity() throws Exception {
        EntityRef position = entity("Q4164871");
        EntityRef king = entity("Q123");
        EntityRef writer = entity("Q456");
        EntityRef kingdom = entity("Q28");
        List<GraphRelation> acquired = new ArrayList<>();
        var graph = graph(List.of(node(SUBCLASS, evidenceCondition(
                GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT))));

        var result = GraphDiscoveryExecutor.execute(new InMemoryGraphStore(), graph,
                List.of(position), (store, demand) -> {
                    acquired.add(demand.relation());
                    if (demand.relation().equals(SUBCLASS)) {
                        store.addEdges(List.of(
                                new GraphEdge(king, SUBCLASS, position, "subclass-1"),
                                new GraphEdge(writer, SUBCLASS, position, "subclass-2")));
                    } else if (demand.relation().equals(JURISDICTION)) {
                        store.addEdges(List.of(
                                new GraphEdge(king, JURISDICTION, kingdom, "jurisdiction"),
                                new GraphEdge(writer, JURISDICTION, entity("Q30"), "country")));
                    } else if (demand.relation().equals(DISSOLVED)) {
                        store.addEdges(List.of(new GraphEdge(kingdom, DISSOLVED,
                                new LiteralValue("time", "1918"), "dissolved")));
                    }
                    store.markCoverage(demand, GraphAdjacencyCoverage.COMPLETE);
                });

        var classified = result.nodes().getFirst();
        assertEquals(List.of(SUBCLASS, JURISDICTION, DISSOLVED), acquired);
        assertEquals(List.of(king, writer), classified.reached());
        assertEquals(List.of(king), classified.accepted());
        assertEquals(List.of(writer), classified.rejected());
        assertEquals("jurisdiction", classified.classifications().getFirst()
                .witnesses().getFirst().evidenceEdge().provenanceId());
        assertEquals("dissolved", classified.classifications().getFirst()
                .witnesses().getFirst().testEdge().provenanceId());
    }

    @Test void anUnacquirableRelationAnywhereInThePlanSpendsNothing() throws Exception {
        // The property the two-phase acquisition test defended before this executor
        // replaced it: "an invalid second hop must not spend or retain the first hop".
        // Checking each demand as it arrives is not the same rule — the first hop is
        // fetched and retained first, and when it returns nothing the bad relation
        // reaches the acquirer with an empty node list and is never examined at all,
        // so an unrunnable graph finishes quietly with everything in Review.
        GraphRelation unacquirable = new GraphRelation("dbpedia", "birthPlace");
        GraphEvidenceCondition condition = new GraphEvidenceCondition(
                "historical polity",
                List.of(GraphPath.direct(JURISDICTION, GraphTraversalDirection.OUTGOING)),
                List.of(new GraphRelationExists(
                        unacquirable, GraphTraversalDirection.OUTGOING)),
                null);
        GraphDiscoveryConfiguration graph = new GraphDiscoveryConfiguration(
                new GraphDiscoveryConfiguration.StartNode("Position", null),
                List.of(new GraphDiscoveryConfiguration.NextNode(
                        SUBCLASS, GraphTraversalDirection.INCOMING,
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY,
                        "", condition)));
        List<String> demanded = new ArrayList<>();
        GraphAdjacencyAcquirer acquirer = new GraphAdjacencyAcquirer() {
            @Override public void acquire(
                    datasource.graph.store.LocalGraphStore store,
                    datasource.graph.store.GraphAdjacencyDemand demand) {
                demanded.add(demand.relation().relationId());
            }
            @Override public void validateRelations(
                    java.util.Collection<GraphRelation> relations) {
                for (GraphRelation relation : relations) {
                    if (!"wikidata".equals(relation.providerId())) {
                        throw new IllegalArgumentException(
                                "cannot acquire " + relation.relationId());
                    }
                }
            }
        };

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> GraphDiscoveryExecutor.execute(new InMemoryGraphStore(), graph,
                        List.of(entity("Q4164871")), acquirer));

        assertEquals("cannot acquire birthPlace", refused.getMessage());
        assertEquals(List.of(), demanded,
                "the refused plan asked for nothing, so no hop was paid for or retained");
    }

    @Test void reviewDispositionAloneDecidesWhetherReviewAdvances() throws Exception {
        assertEquals(List.of(SUBCLASS, JURISDICTION, DISSOLVED, SUCCESSOR),
                acquiredWith(GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT));
        assertEquals(List.of(SUBCLASS, JURISDICTION, DISSOLVED),
                acquiredWith(GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT));
    }

    private static List<GraphRelation> acquiredWith(
            GraphEvidenceCondition.ReviewDisposition disposition) throws Exception {
        EntityRef root = entity("Q4164871");
        EntityRef candidate = entity("Q123");
        EntityRef evidence = entity("Q28");
        List<GraphRelation> acquired = new ArrayList<>();
        var first = node(SUBCLASS, evidenceCondition(disposition));
        var second = new GraphDiscoveryConfiguration.NextNode(SUCCESSOR,
                GraphTraversalDirection.OUTGOING,
                GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY, "", null);

        var result = GraphDiscoveryExecutor.execute(new InMemoryGraphStore(),
                graph(List.of(first, second)), List.of(root), (store, demand) -> {
                    acquired.add(demand.relation());
                    if (demand.relation().equals(SUBCLASS)) {
                        store.addEdges(List.of(new GraphEdge(
                                candidate, SUBCLASS, root, "subclass")));
                        store.markCoverage(demand, GraphAdjacencyCoverage.COMPLETE);
                    } else if (demand.relation().equals(JURISDICTION)) {
                        store.addEdges(List.of(new GraphEdge(
                                candidate, JURISDICTION, evidence, "jurisdiction")));
                        store.markCoverage(demand, GraphAdjacencyCoverage.COMPLETE);
                    } else if (demand.relation().equals(DISSOLVED)) {
                        store.markCoverage(demand, GraphAdjacencyCoverage.UNAVAILABLE);
                    } else {
                        store.markCoverage(demand, GraphAdjacencyCoverage.COMPLETE);
                    }
                });

        assertEquals(List.of(candidate), result.nodes().getFirst().review());
        return acquired;
    }

    private static GraphDiscoveryConfiguration graph(
            List<GraphDiscoveryConfiguration.NextNode> nodes) {
        return new GraphDiscoveryConfiguration(
                new GraphDiscoveryConfiguration.StartNode("Position",
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY), nodes);
    }

    private static GraphDiscoveryConfiguration.NextNode node(
            GraphRelation relation, GraphEvidenceCondition condition) {
        return new GraphDiscoveryConfiguration.NextNode(relation,
                GraphTraversalDirection.INCOMING,
                GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                "Position", condition);
    }

    private static GraphEvidenceCondition evidenceCondition(
            GraphEvidenceCondition.ReviewDisposition disposition) {
        return new GraphEvidenceCondition("Node evidence",
                List.of(GraphPath.direct(JURISDICTION, GraphTraversalDirection.OUTGOING)),
                List.of(new GraphRelationExists(DISSOLVED,
                        GraphTraversalDirection.OUTGOING)), disposition);
    }

    private static GraphRelation relation(String pid) {
        return new GraphRelation("wikidata", pid);
    }
    private static EntityRef entity(String qid) { return EntityRef.wikidata(qid); }
}
