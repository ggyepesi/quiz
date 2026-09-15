package datasource.graph.execution;

import datasource.EntityRef;
import datasource.LiteralValue;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.GraphEvidenceCondition;
import datasource.graph.constraint.GraphPath;
import datasource.graph.constraint.GraphRelationExists;
import datasource.graph.constraint.GraphRelationReachesUnder;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.GraphEdge;
import datasource.graph.store.LocalGraphStore;
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
    private static final GraphRelation COUNTRY = relation("P17");
    private static final GraphRelation DISSOLVED = relation("P576");
    private static final GraphRelation SUCCESSOR = relation("P155");
    private static final GraphRelation INSTANCE_OF = relation("P31");

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

    @Test void evidenceRelationsForTheSameNodesAreOfferedAsOneAcquisition()
            throws Exception {
        EntityRef position = entity("Q4164871");
        EntityRef office = entity("Q123");
        EntityRef polity = entity("Q28");
        List<List<GraphRelation>> acquisitionGroups = new ArrayList<>();
        GraphEvidenceCondition condition = new GraphEvidenceCondition(
                "polity evidence",
                List.of(GraphPath.direct(JURISDICTION,
                                GraphTraversalDirection.OUTGOING),
                        GraphPath.direct(COUNTRY,
                                GraphTraversalDirection.OUTGOING)),
                List.of(new GraphRelationExists(
                        DISSOLVED, GraphTraversalDirection.OUTGOING)), null);
        GraphAdjacencyAcquirer acquirer = new GraphAdjacencyAcquirer() {
            @Override public void acquire(
                    LocalGraphStore store, GraphAdjacencyDemand demand) {
                acquisitionGroups.add(List.of(demand.relation()));
                if (demand.relation().equals(SUBCLASS)) {
                    store.addEdges(List.of(new GraphEdge(
                            office, SUBCLASS, position, "position")));
                }
                store.markCoverage(demand, GraphAdjacencyCoverage.COMPLETE);
            }

            @Override public void acquireAll(
                    LocalGraphStore store,
                    java.util.Collection<GraphAdjacencyDemand> demands) {
                acquisitionGroups.add(demands.stream()
                        .map(GraphAdjacencyDemand::relation).toList());
                for (GraphAdjacencyDemand demand : demands) {
                    if (demand.relation().equals(JURISDICTION)) {
                        store.addEdges(List.of(new GraphEdge(
                                office, JURISDICTION, polity, "jurisdiction")));
                    }
                    store.markCoverage(demand, GraphAdjacencyCoverage.COMPLETE);
                }
            }
        };

        GraphDiscoveryExecutor.execute(new InMemoryGraphStore(),
                graph(List.of(node(SUBCLASS, condition))), List.of(position), acquirer);

        assertEquals(List.of(
                List.of(SUBCLASS),
                List.of(JURISDICTION, COUNTRY),
                List.of(DISSOLVED)), acquisitionGroups);
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

    @Test void aGeneralisedJurisdictionIsAcceptedWithoutNamingEveryKindOfPolity()
            throws Exception {
        // The four offices the shipped condition rejected, each with the jurisdiction
        // type that equality could not match: a test naming country, empire and
        // historical country says no to all of them, and naming eyalet and exarchate too
        // only waits for the khanate. Each type IS a polity, by P279, and the source says
        // so — the test follows the generalisation instead of enumerating its instances.
        EntityRef root = entity("Q114962596");
        EntityRef polity = entity("Q7275");
        EntityRef confederate = entity("Q1999471");
        EntityRef dey = entity("Q3025636");
        EntityRef exarch = entity("Q27831917");
        EntityRef bishop = entity("Q22881");
        EntityRef unrecognised = entity("Q99541706");
        EntityRef eyalet = entity("Q44565");
        EntityRef exarchate = entity("Q946136");
        EntityRef spiritual = entity("Q1499065");
        EntityRef territory = entity("Q15642541");

        var condition = new GraphEvidenceCondition("Node evidence",
                List.of(GraphPath.direct(JURISDICTION, GraphTraversalDirection.OUTGOING)),
                List.of(GraphRelationReachesUnder.of(INSTANCE_OF,
                        GraphTraversalDirection.OUTGOING, polity, SUBCLASS)),
                GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT);

        var result = GraphDiscoveryExecutor.execute(new InMemoryGraphStore(),
                graph(List.of(node(INSTANCE_OF, condition))), List.of(root),
                (store, demand) -> {
                    List<GraphEdge> edges = new ArrayList<>();
                    if (demand.relation().equals(INSTANCE_OF)
                            && demand.direction() == GraphTraversalDirection.INCOMING) {
                        for (EntityRef office
                                : List.of(confederate, dey, exarch, bishop)) {
                            edges.add(new GraphEdge(office, INSTANCE_OF, root, "p31"));
                        }
                    } else if (demand.relation().equals(JURISDICTION)) {
                        edges.add(new GraphEdge(confederate, JURISDICTION,
                                entity("Q0001"), "j1"));
                        edges.add(new GraphEdge(dey, JURISDICTION, entity("Q0002"), "j2"));
                        edges.add(new GraphEdge(exarch, JURISDICTION,
                                entity("Q0003"), "j3"));
                        edges.add(new GraphEdge(bishop, JURISDICTION,
                                entity("Q0004"), "j4"));
                    } else if (demand.relation().equals(INSTANCE_OF)) {
                        edges.add(new GraphEdge(entity("Q0001"), INSTANCE_OF,
                                unrecognised, "t1"));
                        edges.add(new GraphEdge(entity("Q0002"), INSTANCE_OF,
                                eyalet, "t2"));
                        edges.add(new GraphEdge(entity("Q0003"), INSTANCE_OF,
                                exarchate, "t3"));
                        edges.add(new GraphEdge(entity("Q0004"), INSTANCE_OF,
                                spiritual, "t4"));
                    } else if (demand.relation().equals(SUBCLASS)) {
                        // None of the four is a polity directly; each generalises to one,
                        // and the spiritual territory needs two hops to get there.
                        edges.add(new GraphEdge(unrecognised, SUBCLASS, polity, "s1"));
                        edges.add(new GraphEdge(eyalet, SUBCLASS, polity, "s2"));
                        edges.add(new GraphEdge(exarchate, SUBCLASS, polity, "s3"));
                        edges.add(new GraphEdge(spiritual, SUBCLASS, territory, "s4"));
                        edges.add(new GraphEdge(territory, SUBCLASS, polity, "s5"));
                    }
                    store.addEdges(edges);
                    store.markCoverage(demand, GraphAdjacencyCoverage.COMPLETE);
                });

        assertEquals(List.of(confederate, dey, exarch, bishop),
                result.nodes().getFirst().accepted(),
                "every jurisdiction that generalises to a polity is accepted, "
                        + "however it is named");
        assertEquals(List.of(), result.nodes().getFirst().review());
    }

    @Test void aJurisdictionThatGeneralisesToSomethingElseIsStillRefused()
            throws Exception {
        // The other side: widening the test must not turn it into "has a jurisdiction".
        EntityRef root = entity("Q114962596");
        EntityRef polity = entity("Q7275");
        EntityRef office = entity("Q123");
        EntityRef horse = entity("Q726");

        var condition = new GraphEvidenceCondition("Node evidence",
                List.of(GraphPath.direct(JURISDICTION, GraphTraversalDirection.OUTGOING)),
                List.of(GraphRelationReachesUnder.of(INSTANCE_OF,
                        GraphTraversalDirection.OUTGOING, polity, SUBCLASS)),
                GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT);

        var result = GraphDiscoveryExecutor.execute(new InMemoryGraphStore(),
                graph(List.of(node(INSTANCE_OF, condition))), List.of(root),
                (store, demand) -> {
                    List<GraphEdge> edges = new ArrayList<>();
                    if (demand.relation().equals(INSTANCE_OF)
                            && demand.direction() == GraphTraversalDirection.INCOMING) {
                        edges.add(new GraphEdge(office, INSTANCE_OF, root, "p31"));
                    } else if (demand.relation().equals(JURISDICTION)) {
                        edges.add(new GraphEdge(office, JURISDICTION, entity("Q9"), "j"));
                    } else if (demand.relation().equals(INSTANCE_OF)) {
                        edges.add(new GraphEdge(entity("Q9"), INSTANCE_OF, horse, "t"));
                    }
                    store.addEdges(edges);
                    store.markCoverage(demand, GraphAdjacencyCoverage.COMPLETE);
                });

        assertEquals(List.of(), result.nodes().getFirst().accepted());
        assertEquals(List.of(office), result.nodes().getFirst().rejected());
    }

    @Test void anUnfetchedGeneralisationHopIsReviewedRatherThanRefused()
            throws Exception {
        // The hop that would have matched may simply not have been fetched. Refusing on
        // it turns a gap in acquisition into a verdict about the world.
        EntityRef root = entity("Q114962596");
        EntityRef polity = entity("Q7275");
        EntityRef office = entity("Q123");
        EntityRef eyalet = entity("Q44565");

        var condition = new GraphEvidenceCondition("Node evidence",
                List.of(GraphPath.direct(JURISDICTION, GraphTraversalDirection.OUTGOING)),
                List.of(GraphRelationReachesUnder.of(INSTANCE_OF,
                        GraphTraversalDirection.OUTGOING, polity, SUBCLASS)),
                GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT);

        var result = GraphDiscoveryExecutor.execute(new InMemoryGraphStore(),
                graph(List.of(node(INSTANCE_OF, condition))), List.of(root),
                (store, demand) -> {
                    List<GraphEdge> edges = new ArrayList<>();
                    if (demand.relation().equals(INSTANCE_OF)
                            && demand.direction() == GraphTraversalDirection.INCOMING) {
                        edges.add(new GraphEdge(office, INSTANCE_OF, root, "p31"));
                    } else if (demand.relation().equals(JURISDICTION)) {
                        edges.add(new GraphEdge(office, JURISDICTION, entity("Q9"), "j"));
                    } else if (demand.relation().equals(INSTANCE_OF)) {
                        edges.add(new GraphEdge(entity("Q9"), INSTANCE_OF, eyalet, "t"));
                    }
                    store.addEdges(edges);
                    // The generalisation itself could not be fetched.
                    store.markCoverage(demand, demand.relation().equals(SUBCLASS)
                            ? GraphAdjacencyCoverage.UNAVAILABLE
                            : GraphAdjacencyCoverage.COMPLETE);
                });

        assertEquals(List.of(office), result.nodes().getFirst().review(),
                "an unknown generalisation is reviewed, not refused");
        assertEquals(List.of(), result.nodes().getFirst().rejected());
    }

    @Test void runningOutOfDepthIsARefusalRatherThanAReview() throws Exception {
        // P279 never exhausts — everything generalises to "entity" eventually — so a
        // bound treated as "unknown" makes refusal unreachable and sends every
        // non-matching node to review. The bound is the scope the test declares.
        EntityRef root = entity("Q114962596");
        EntityRef target = entity("Q56061");
        EntityRef office = entity("Q123");
        EntityRef near = entity("Q9001");
        EntityRef far = entity("Q9002");

        var condition = new GraphEvidenceCondition("Node evidence",
                List.of(GraphPath.direct(JURISDICTION, GraphTraversalDirection.OUTGOING)),
                List.of(new GraphRelationReachesUnder(INSTANCE_OF,
                        GraphTraversalDirection.OUTGOING, target, SUBCLASS, 1)),
                GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT);

        var result = GraphDiscoveryExecutor.execute(new InMemoryGraphStore(),
                graph(List.of(node(INSTANCE_OF, condition))), List.of(root),
                (store, demand) -> {
                    List<GraphEdge> edges = new ArrayList<>();
                    if (demand.relation().equals(INSTANCE_OF)
                            && demand.direction() == GraphTraversalDirection.INCOMING) {
                        edges.add(new GraphEdge(office, INSTANCE_OF, root, "p31"));
                    } else if (demand.relation().equals(JURISDICTION)) {
                        edges.add(new GraphEdge(office, JURISDICTION, entity("Q9"), "j"));
                    } else if (demand.relation().equals(INSTANCE_OF)) {
                        edges.add(new GraphEdge(entity("Q9"), INSTANCE_OF, near, "t"));
                    } else if (demand.relation().equals(SUBCLASS)) {
                        // The target is two hops away; the test allows one.
                        edges.add(new GraphEdge(near, SUBCLASS, far, "s1"));
                        edges.add(new GraphEdge(far, SUBCLASS, target, "s2"));
                    }
                    store.addEdges(edges);
                    store.markCoverage(demand, GraphAdjacencyCoverage.COMPLETE);
                });

        assertEquals(List.of(office), result.nodes().getFirst().rejected(),
                "a target beyond the declared depth is refused, not reviewed");
        assertEquals(List.of(), result.nodes().getFirst().review());
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
