package wikidata.explore.model;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.GraphEvidenceCondition;
import datasource.graph.constraint.GraphPath;
import datasource.graph.constraint.GraphRelationReaches;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphDiscoveryConfigurationPersistenceTest {
    @TempDir Path temp;

    @Test void startAndPopulationRolesAreIndependentAndRoundTrip() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("History");
        project.rootClass(new GeneratedClassModel("Position"));
        project.rootClass().seedQids().add("Q4164871");
        project.graphDiscoveryConfiguration(configuration());

        Path file = temp.resolve("history.model.json");
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(project, file.toFile());
        GeneratedProjectModel loaded = store.load(file.toFile());

        GraphDiscoveryConfiguration restored = loaded.graphDiscoveryConfiguration();
        assertEquals(GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY,
                restored.startNode().use());
        assertEquals("Position", restored.startNode().qidSourceClass());
        assertEquals(GraphTraversalDirection.INCOMING,
                restored.nextNodes().getFirst().directionFromPrevious());
        assertEquals(GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                restored.nextNodes().getFirst().use());
        assertEquals("Position", restored.nextNodes().getFirst().populationClass());
        assertEquals("P1001", restored.nextNodes().getFirst().evidenceCondition()
                .evidencePaths().getFirst().relation().relationId());
        assertEquals("P31", restored.nextNodes().getFirst().evidenceCondition()
                .tests().getFirst().relation().relationId());
        assertEquals(GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT,
                restored.nextNodes().getFirst().evidenceCondition().reviewDisposition());
    }

    @Test void projectCopyCarriesTheAuthoredGraph() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.graphDiscoveryConfiguration(configuration());
        assertEquals(configuration(), project.copy().graphDiscoveryConfiguration());
    }

    @Test void classRenameRetargetsBothGraphUses() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(new GeneratedClassModel("Position"));
        project.graphDiscoveryConfiguration(configuration());

        project.renameClass("Position", "Office");

        assertEquals("Office", project.graphDiscoveryConfiguration()
                .startNode().qidSourceClass());
        assertEquals("Office", project.graphDiscoveryConfiguration()
                .nextNodes().getFirst().populationClass());
    }

    private static GraphDiscoveryConfiguration configuration() {
        return new GraphDiscoveryConfiguration(
                new GraphDiscoveryConfiguration.StartNode("Position",
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(new GraphDiscoveryConfiguration.NextNode(
                        new GraphRelation("wikidata", "P279"),
                        GraphTraversalDirection.INCOMING,
                        GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                        "Position",
                        new GraphEvidenceCondition("Node evidence",
                                List.of(GraphPath.direct(
                                        new GraphRelation("wikidata", "P1001"),
                                        GraphTraversalDirection.OUTGOING)),
                                List.of(new GraphRelationReaches(
                                        new GraphRelation("wikidata", "P31"),
                                        GraphTraversalDirection.OUTGOING,
                                        EntityRef.wikidata("Q4164871"))),
                                GraphEvidenceCondition.ReviewDisposition
                                        .EXCLUDE_AND_REPORT))));
    }
}
