package datasource.graph.constraint;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.GraphEdge;
import datasource.graph.store.InMemoryGraphStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the first node-admission slice: alternatives are any-of, positive evidence can
 * decide early, while rejection requires reached evidence and complete two-hop facts.
 */
class GraphEvidenceConditionsTest {
    private static final GraphTraversalDirection OUT = GraphTraversalDirection.OUTGOING;
    private static final GraphRelation JURISDICTION = relation("jurisdiction");
    private static final GraphRelation COUNTRY = relation("country");
    private static final GraphRelation DISSOLVED = relation("dissolved");
    private static final GraphRelation KIND = relation("kind");

    private final EntityRef position = entity("position");
    private final EntityRef polity = entity("polity");
    private final EntityRef historicalKind = entity("historical-kind");

    @Test void aPositiveWitnessAcceptsAndRetainsBothHopsAndTheirCoverage() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphEdge evidence = edge(store, position, JURISDICTION, polity);
        GraphEdge witness = edge(store, polity, DISSOLVED, entity("date"));
        covered(store, position, JURISDICTION, COUNTRY);
        covered(store, polity, DISSOLVED, KIND);

        GraphEvidenceConditionResult result = GraphEvidenceConditions.evaluate(
                store, condition(null), position);

        assertEquals(GraphEvidenceConditionResult.Decision.ACCEPTED, result.decision());
        assertTrue(result.includedInPopulation());
        assertEquals(List.of(evidence), result.evidenceEdges());
        assertTrue(result.testEdges().contains(witness));
        assertEquals(List.of(new GraphEvidenceWitness(evidence, witness)),
                result.witnesses());
        assertTrue(result.coverage().contains(new GraphAdjacencyObservation(
                position, JURISDICTION, OUT, GraphAdjacencyCoverage.COMPLETE)));
        assertTrue(result.coverage().contains(new GraphAdjacencyObservation(
                polity, DISSOLVED, OUT, GraphAdjacencyCoverage.COMPLETE)));
    }

    @Test void anyAlternativeEvidenceRelationAndAnyAlternativeTestMayAccept() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        edge(store, position, COUNTRY, polity);
        GraphEdge otherKind = edge(store, polity, KIND, entity("other-kind"));
        GraphEdge matchingKind = edge(store, polity, KIND, historicalKind);
        covered(store, position, JURISDICTION, COUNTRY);
        covered(store, polity, DISSOLVED, KIND);

        GraphEvidenceConditionResult result = GraphEvidenceConditions.evaluate(
                store, condition(null), position);

        assertEquals(GraphEvidenceConditionResult.Decision.ACCEPTED, result.decision());
        assertTrue(result.testEdges().containsAll(List.of(otherKind, matchingKind)));
        assertEquals(List.of(matchingKind),
                result.witnesses().stream().map(GraphEvidenceWitness::testEdge).toList());
    }

    @Test void completeButEmptyEvidenceIsReviewAndIsIncludedByDefault() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        covered(store, position, JURISDICTION, COUNTRY);

        GraphEvidenceConditionResult result = GraphEvidenceConditions.evaluate(
                store, condition(null), position);

        assertEquals(GraphEvidenceConditionResult.Decision.REVIEW, result.decision());
        assertTrue(result.includedInPopulation());
        assertEquals(2, result.coverage().size());
    }

    @Test void anExplicitReviewDispositionMayExcludeWithoutHidingReview() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        covered(store, position, JURISDICTION, COUNTRY);

        GraphEvidenceConditionResult result = GraphEvidenceConditions.evaluate(
                store, condition(GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT),
                position);

        assertEquals(GraphEvidenceConditionResult.Decision.REVIEW, result.decision());
        assertFalse(result.includedInPopulation());
        assertEquals(GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT,
                result.reviewDisposition());
    }

    @Test void reachedEvidenceWithCompleteContradictingTestsIsRejected() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphEdge evidence = edge(store, position, JURISDICTION, polity);
        GraphEdge currentKind = edge(store, polity, KIND, entity("current-kind"));
        covered(store, position, JURISDICTION, COUNTRY);
        covered(store, polity, DISSOLVED, KIND);

        GraphEvidenceConditionResult result = GraphEvidenceConditions.evaluate(
                store, condition(null), position);

        assertEquals(GraphEvidenceConditionResult.Decision.REJECTED, result.decision());
        assertFalse(result.includedInPopulation());
        assertEquals(List.of(evidence), result.evidenceEdges());
        assertEquals(List.of(currentKind), result.testEdges());
        assertEquals(List.of(), result.witnesses());
    }

    @Test void incompleteTestCoverageWithoutAPositiveWitnessIsReview() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        edge(store, position, JURISDICTION, polity);
        covered(store, position, JURISDICTION, COUNTRY);
        covered(store, polity, KIND); // dissolved deliberately unknown

        GraphEvidenceConditionResult result = GraphEvidenceConditions.evaluate(
                store, condition(null), position);

        assertEquals(GraphEvidenceConditionResult.Decision.REVIEW, result.decision());
        assertTrue(result.includedInPopulation());
    }

    @Test void aPositiveWitnessDecidesAnyDespiteAnotherUnavailableAlternative() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        edge(store, position, JURISDICTION, polity);
        GraphEdge witness = edge(store, polity, DISSOLVED, entity("date"));
        covered(store, position, JURISDICTION); // country deliberately unknown
        covered(store, polity, DISSOLVED);      // kind deliberately unknown

        GraphEvidenceConditionResult result = GraphEvidenceConditions.evaluate(
                store, condition(null), position);

        assertEquals(GraphEvidenceConditionResult.Decision.ACCEPTED, result.decision());
        assertEquals(List.of(witness),
                result.witnesses().stream().map(GraphEvidenceWitness::testEdge).toList());
    }

    @Test void aWitnessNamesTheEvidenceRelationThatCarriedIt() {
        // Apostolic King of Hungary reaches the Kingdom of Hungary through BOTH
        // jurisdiction and country. A flat list of test edges cannot say which one
        // justified the node, and per-relation attribution is the measurement the
        // design quotes.
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphEdge viaJurisdiction = edge(store, position, JURISDICTION, polity);
        GraphEdge viaCountry = edge(store, position, COUNTRY, polity);
        GraphEdge dissolved = edge(store, polity, DISSOLVED, entity("date"));
        covered(store, position, JURISDICTION, COUNTRY);
        covered(store, polity, DISSOLVED, KIND);

        GraphEvidenceConditionResult result = GraphEvidenceConditions.evaluate(
                store, condition(null), position);

        assertEquals(GraphEvidenceConditionResult.Decision.ACCEPTED, result.decision());
        assertEquals(List.of(
                        new GraphEvidenceWitness(viaJurisdiction, dissolved),
                        new GraphEvidenceWitness(viaCountry, dissolved)),
                result.witnesses(),
                "both relations reached the same polity and both justify the node");
    }

    @Test void anAbsenceTestAcceptsWithNoWitnessEdgeToShow() {
        // Acceptance is the test's verdict, not the presence of a witness: absence
        // matches by there being no edge, so keying acceptance on a non-empty witness
        // list would silently drop it.
        InMemoryGraphStore store = new InMemoryGraphStore();
        GraphEdge evidence = edge(store, position, JURISDICTION, polity);
        covered(store, position, JURISDICTION, COUNTRY);
        covered(store, polity, DISSOLVED);

        GraphEvidenceConditionResult result = GraphEvidenceConditions.evaluate(
                store,
                new GraphEvidenceCondition(
                        "polity asserts no dissolution",
                        List.of(GraphPath.direct(JURISDICTION, OUT)),
                        List.of(new GraphRelationAbsent(DISSOLVED, OUT)),
                        null),
                position);

        assertEquals(GraphEvidenceConditionResult.Decision.ACCEPTED, result.decision());
        assertTrue(result.includedInPopulation());
        assertEquals(List.of(), result.witnesses());
        assertEquals(List.of(evidence), result.evidenceEdges());
    }

    private GraphEvidenceCondition condition(
            GraphEvidenceCondition.ReviewDisposition disposition) {
        return new GraphEvidenceCondition(
                "historical polity",
                List.of(GraphPath.direct(JURISDICTION, OUT),
                        GraphPath.direct(COUNTRY, OUT)),
                List.of(new GraphRelationExists(DISSOLVED, OUT),
                        new GraphRelationReaches(KIND, OUT, historicalKind)),
                disposition);
    }

    private static GraphRelation relation(String id) {
        return new GraphRelation("catalogue", id);
    }

    private static EntityRef entity(String id) {
        return new EntityRef("catalogue", id);
    }

    private static GraphEdge edge(
            InMemoryGraphStore store,
            EntityRef source,
            GraphRelation relation,
            EntityRef target) {
        GraphEdge edge = new GraphEdge(source, relation, target, "test");
        store.addEdges(List.of(edge));
        return edge;
    }

    private static void covered(
            InMemoryGraphStore store, EntityRef node, GraphRelation... relations) {
        for (GraphRelation relation : relations) {
            store.markCoverage(new GraphAdjacencyDemand(List.of(node), relation, OUT),
                    GraphAdjacencyCoverage.COMPLETE);
        }
    }
}
