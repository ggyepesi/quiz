package wikidata.explore.generation;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.GraphEvidenceCondition;
import datasource.graph.constraint.GraphEvidenceConditionResult;
import datasource.graph.execution.GraphDiscoveryExecutor;
import datasource.graph.store.GraphEdge;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelValidator;
import wikidata.explore.model.GraphClassSource;
import wikidata.explore.query.logical.ConfiguredGraphDiscoveryQuery;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rejected entry takes what was reached only through it out of the result (#312).
 *
 * <p>A repeated output node — a replacement chain — walks from every entry it may and
 * decides afterwards, so the reviewer sees what each one leads to. Rejecting an entry then
 * left everything reached only through it in the population, and so did an entry the
 * evidence rejected: a member admitted by way of a non-member. Each entry now records
 * what it was reached from, and a result counts an entry only while a chain from the start
 * still reaches it through entries the walk continues through.
 */
class GraphPruningTest {

    private static final GraphRelation REPLACES = new GraphRelation("wikidata", "P1365");

    /** Start Q1 replaces Q2, which replaces Q3: Q3 is reached only through Q2. */
    @Test void rejectingAnEntryTakesWhatWasReachedOnlyThroughItOut() {
        GraphDiscoveryResultStore.Artifact chain = chain("Accepted", "Accepted");
        assertEquals(Set.of("Q2", "Q3"), chain.acceptedIdentities());

        GraphDiscoveryResultStore.manualDecision(entry(chain, "Q2"), "Rejected");
        assertEquals(Set.of(), chain.acceptedIdentities(),
                "Q3 was reached only through the rejected Q2");

        GraphDiscoveryResultStore.manualDecision(entry(chain, "Q2"), null);
        assertEquals(Set.of("Q2", "Q3"), chain.acceptedIdentities(),
                "clearing the decision brings it back, without running again");
    }

    @Test void anEntryTheEvidenceRejectedDoesNotAdmitWhatLiesBeyondIt() {
        GraphDiscoveryResultStore.Artifact chain = chain("Rejected", "Accepted");

        assertEquals(Set.of(), chain.acceptedIdentities());
    }

    /** An entry cut off by a rejection is not in the result, so it is not waited for. */
    @Test void aCutOffEntryIsNotAwaitingADecision() {
        GraphDiscoveryResultStore.Artifact chain = chain("Accepted", "Review");
        assertEquals(List.of("Q3"), chain.awaitingDecision().stream()
                .map(WikidataDynamicObject::getIdentifier).toList());

        GraphDiscoveryResultStore.manualDecision(entry(chain, "Q2"), "Rejected");
        assertTrue(chain.awaitingDecision().isEmpty());
    }

    /** The other side: an entry also reached another way stays. */
    @Test void anEntryWithAnotherWayInStays() {
        GraphDiscoveryResultStore.Artifact chain = chain("Accepted", "Accepted",
                new GraphEdge(EntityRef.wikidata("Q1"), REPLACES, EntityRef.wikidata("Q3"), "p"));

        GraphDiscoveryResultStore.manualDecision(entry(chain, "Q2"), "Rejected");
        assertEquals(Set.of("Q3"), chain.acceptedIdentities());
    }

    /** A result saved before predecessors were recorded is counted as it always was. */
    @Test void aResultWithoutRecordedPredecessorsIsNotPruned() {
        GraphDiscoveryResultStore.Artifact chain = chain("Accepted", "Accepted");
        chain.instances().forEach(value -> value.remove(GraphDiscoveryResultStore.REACHED_FROM));

        GraphDiscoveryResultStore.manualDecision(entry(chain, "Q2"), "Rejected");
        assertEquals(Set.of("Q3"), chain.acceptedIdentities());
    }

    @Test void onlyTheOutputNodeMayWaitForADecision() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Offices");
        model.rootClass().className("Position");
        GeneratedClassModel graph = model.getOrCreateClass("PositionGraph");
        graph.classKind(ClassKind.GRAPH);
        graph.graphSource(new GraphClassSource(
                new GraphDiscoveryConfiguration.StartNode("Position", "",
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(node(GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY,
                                GraphEvidenceCondition.ReviewDisposition.AWAIT_DECISION),
                        node(GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                                GraphEvidenceCondition.ReviewDisposition.AWAIT_DECISION))));

        List<String> errors = GeneratedProjectModelValidator.validate(model).errors().stream()
                .map(GeneratedProjectModelValidator.Problem::message).toList();

        assertEquals(1, errors.stream().filter(message -> message.contains("intermediate")).count(),
                errors.toString());
        assertTrue(errors.getFirst().startsWith("Graph node 1 is intermediate"), errors.toString());
    }

    private static GraphDiscoveryConfiguration.NextNode node(
            GraphDiscoveryConfiguration.NodeUse use,
            GraphEvidenceCondition.ReviewDisposition disposition) {
        return new GraphDiscoveryConfiguration.NextNode(REPLACES, GraphTraversalDirection.OUTGOING,
                use, use == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION ? "Position" : "",
                new GraphEvidenceCondition("Evidence",
                        List.of(datasource.graph.constraint.GraphPath.direct(
                                new GraphRelation("wikidata", "P17"),
                                GraphTraversalDirection.OUTGOING)),
                        List.of(new datasource.graph.constraint.GraphRelationAbsent(
                                new GraphRelation("wikidata", "P576"),
                                GraphTraversalDirection.OUTGOING)), disposition));
    }

    private static WikidataDynamicObject entry(GraphDiscoveryResultStore.Artifact result,
                                               String qid) {
        return result.instances().stream().filter(value -> qid.equals(value.getIdentifier()))
                .findFirst().orElseThrow();
    }

    /** A repeated output node from start Q1: Q1 replaces Q2, Q2 replaces Q3. */
    private static GraphDiscoveryResultStore.Artifact chain(String q2, String q3,
                                                            GraphEdge... extra) {
        EntityRef start = EntityRef.wikidata("Q1");
        EntityRef second = EntityRef.wikidata("Q2");
        EntityRef third = EntityRef.wikidata("Q3");
        List<GraphEdge> edges = new java.util.ArrayList<>(List.of(
                new GraphEdge(start, REPLACES, second, "p"),
                new GraphEdge(second, REPLACES, third, "p")));
        edges.addAll(List.of(extra));
        GraphDiscoveryConfiguration.NextNode output = new GraphDiscoveryConfiguration.NextNode(
                REPLACES, GraphTraversalDirection.OUTGOING,
                GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION, "Position", null,
                List.of(), GraphDiscoveryConfiguration.PopulationOperation.ADD, true, "");
        GraphDiscoveryExecutor.NodeResult node = new GraphDiscoveryExecutor.NodeResult(
                1, output, new datasource.graph.GraphTraversalStep("step", "Start", "Position",
                        "Graph", REPLACES, GraphTraversalDirection.OUTGOING,
                        datasource.graph.GraphExpansionPolicy.CURATED),
                List.of(second, third), List.of(), List.of(), List.of(),
                List.of(classified(second, q2), classified(third, q3)),
                edges, List.of(), List.of());
        return GraphDiscoveryResultStore.artifact("Offices", "PositionGraph",
                new ConfiguredGraphDiscoveryQuery.Result(
                        new GraphDiscoveryExecutor.Result(List.of(start), List.of(node)),
                        Map.of("Q1", "Start", "Q2", "Second", "Q3", "Third"), 3));
    }

    private static GraphEvidenceConditionResult classified(EntityRef entity, String decision) {
        return new GraphEvidenceConditionResult(switch (decision) {
            case "Accepted" -> GraphEvidenceConditionResult.Decision.ACCEPTED;
            case "Rejected" -> GraphEvidenceConditionResult.Decision.REJECTED;
            default -> GraphEvidenceConditionResult.Decision.REVIEW;
        }, entity, "Evidence", GraphEvidenceCondition.ReviewDisposition.AWAIT_DECISION,
                List.of(), List.of(), List.of(), List.of(), "");
    }
}
