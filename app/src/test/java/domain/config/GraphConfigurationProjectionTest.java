package domain.config;

import domain.datasource.EntityRef;
import domain.datasource.graph.GraphDiscoveryConfiguration;
import domain.datasource.graph.GraphRelation;
import domain.datasource.graph.GraphTraversalDirection;
import domain.datasource.graph.constraint.GraphEvidenceCondition;
import domain.datasource.graph.constraint.GraphPath;
import domain.datasource.graph.constraint.GraphRelationReaches;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forces #557's graph projection independently of its Swing host. */
class GraphConfigurationProjectionTest {

    @Test void graphProjectsStartOrderedPathAlternativesEvidenceAndClassDetails() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        GeneratedClassModel result = new GeneratedClassModel("ReachablePosition");
        GeneratedClassModel graph = new GeneratedClassModel("PositionReachability");
        project.rootClass(position);
        project.addClass(result);
        project.addClass(graph);
        graph.classKind(ClassKind.GRAPH);
        PopulationSelection population = new PopulationSelection("PositionsWithHolders");
        population.className("Position");
        population.instanceQids(List.of("Q1"));
        project.addSelection(population);
        PopulationSelection frontier = new PopulationSelection("Frontier");
        frontier.className("Position");
        frontier.instanceQids(List.of("Q2"));
        project.addSelection(frontier);
        project.ensureDeclarationIdentities();

        GraphEvidenceCondition evidence = new GraphEvidenceCondition("Has holder",
                List.of(GraphPath.direct(new GraphRelation("wikidata", "P39"),
                        GraphTraversalDirection.INCOMING)),
                List.of(new GraphRelationReaches(new GraphRelation("wikidata", "P31"),
                        GraphTraversalDirection.OUTGOING, EntityRef.wikidata("Q5"))),
                GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT);
        graph.graphSource(new GraphClassSource(
                new GraphDiscoveryConfiguration.StartNode("", population.name(),
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY,
                        population.declarationId()),
                List.of(new GraphDiscoveryConfiguration.NextNode(
                        new GraphRelation("wikidata", "P31"),
                        GraphTraversalDirection.OUTGOING,
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY,
                        "", null).withAdmission(frontier.declarationId(), frontier.name()),
                        new GraphDiscoveryConfiguration.NextNode(
                        new GraphRelation("wikidata", "P39"),
                        GraphTraversalDirection.INCOMING,
                        GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                        result.className(), evidence,
                        List.of(new GraphDiscoveryConfiguration.Edge(
                                new GraphRelation("wikidata", "P106"),
                                GraphTraversalDirection.OUTGOING)),
                        GraphDiscoveryConfiguration.PopulationOperation.ADD, true,
                        population.name(), population.declarationId()))));

        ConfigurationNode root = GraphConfigurationProjection.project(project, graph);
        List<ConfigurationNode> nodes = flatten(root);
        List<String> captions = nodes.stream().map(ConfigurationNode::caption).toList();

        assertTrue(captions.contains("Start: population PositionsWithHolders"));
        assertTrue(captions.contains("Path — 2 nodes"));
        assertTrue(captions.contains("Node 2 — P39 in"));
        assertTrue(captions.contains("Incoming edge: P39 in"));
        assertTrue(captions.contains("Alternative: P106 out"));
        assertTrue(captions.contains("Output: ReachablePosition (add)"));
        assertTrue(captions.contains("Repeat until stable"));
        assertTrue(captions.contains("Evidence: Has holder"));
        assertTrue(captions.contains("Relation: P39 in"));
        assertTrue(captions.contains("Test: P31 out reaches Q5"));
        assertTrue(captions.contains("Incomplete evidence: Exclude unless manually accepted"));
        assertTrue(captions.contains("Identity"));
        assertTrue(captions.contains("Display name"));
        assertEquals(4, nodes.stream()
                .filter(node -> node.kind() == ConfigurationNode.Kind.REFERENCE).count(),
                "start population, output class and both admission populations are navigable");
        assertEquals(frontier.declarationId(), nodes.stream()
                .filter(node -> node.id().key().equals("path/node:0/admission-reference"))
                .findFirst().orElseThrow().referenceTarget().declarationId());
        assertEquals(population.declarationId(), nodes.stream()
                .filter(node -> node.id().key().equals("path/node:1/admission-reference"))
                .findFirst().orElseThrow().referenceTarget().declarationId());
        var outputReference = ConfigurationReferenceInventory.of(project)
                .referencesToClass(result).stream()
                .filter(reference -> reference.relation()
                        == ConfigurationReferenceInventory.Relation.GRAPH_OUTPUT_CLASS)
                .findFirst().orElseThrow();
        assertEquals("path/node:1", outputReference.source().fieldPath());
        assertEquals("PositionReachability node 2", outputReference.source().displayName(),
                "the incoming reference identifies its graph and one-based node number");
        assertEquals(1, GraphConfigurationProjection.pathNodeIndex(nodes.stream()
                .filter(node -> node.caption().startsWith("Alternative:"))
                .findFirst().orElseThrow().id()));

        ConfigurationNode draft = GraphConfigurationProjection.project(project, graph,
                graph.graphSource().startNode(), List.of());
        assertEquals(0, flatten(draft).stream()
                .filter(node -> node.kind() == ConfigurationNode.Kind.REFERENCE).count(),
                "saved node positions must not be attached to a different editor draft");
    }

    @Test void emptyGraphShowsExplicitStartAndPathSlotsWithoutMutatingTheModel() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel graph = new GeneratedClassModel("Graph");
        project.rootClass(graph);
        graph.classKind(ClassKind.GRAPH);
        project.ensureDeclarationIdentities();

        ConfigurationNode root = GraphConfigurationProjection.project(project, graph);
        List<ConfigurationNode> nodes = flatten(root);

        assertTrue(nodes.stream().anyMatch(node -> node.caption().equals(
                "+ Choose start class or population")
                && node.kind() == ConfigurationNode.Kind.AVAILABLE_SLOT));
        assertTrue(nodes.stream().anyMatch(node -> node.caption().equals("+ Add next node")
                && node.kind() == ConfigurationNode.Kind.AVAILABLE_SLOT));
        assertNull(graph.graphSource(), "inspection must not author a graph");
    }

    private static List<ConfigurationNode> flatten(ConfigurationNode root) {
        List<ConfigurationNode> result = new ArrayList<>();
        add(root, result);
        return result;
    }

    private static void add(ConfigurationNode node, List<ConfigurationNode> result) {
        result.add(node);
        node.children().forEach(child -> add(child, result));
    }
}
