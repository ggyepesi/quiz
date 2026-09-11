package wikidata.explore.model;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.GraphEvidenceCondition;
import datasource.graph.constraint.GraphPath;
import datasource.graph.constraint.GraphRelationExists;
import datasource.graph.constraint.GraphRelationReaches;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GraphAdmissionConditionPersistenceTest {
    @TempDir Path temp;

    @Test void aClassOwnsAndRoundTripsItsCoverageAwarePopulationCondition() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("History");
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.membership(EntityBound.relation("P31", List.of("Q4164871"), false));
        position.graphAdmissionCondition(new GraphEvidenceCondition(
                "historical polity",
                List.of(path("P1001"), path("P17"), path("P2389")),
                List.of(
                        new GraphRelationExists(relation("P576"),
                                GraphTraversalDirection.OUTGOING),
                        new GraphRelationReaches(relation("P31"),
                                GraphTraversalDirection.OUTGOING,
                                EntityRef.wikidata("Q3024240"))),
                GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT));
        project.rootClass(position);

        Path file = temp.resolve("history.model.json");
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(project, file.toFile());
        GeneratedProjectModel loaded = store.load(file.toFile());

        GraphEvidenceCondition restored = loaded.rootClass().graphAdmissionCondition();
        assertEquals("historical polity", restored.name());
        assertEquals(List.of("P1001", "P17", "P2389"), restored.evidencePaths().stream()
                .map(path -> path.relation().relationId()).toList());
        assertInstanceOf(GraphRelationExists.class, restored.tests().get(0));
        GraphRelationReaches reaches = assertInstanceOf(
                GraphRelationReaches.class, restored.tests().get(1));
        assertEquals("Q3024240", reaches.entity().id());
    }

    @Test void projectCopiesCarryTheSameAuthoredCondition() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass().graphAdmissionCondition(new GraphEvidenceCondition(
                "notable relation", List.of(path("P1001")),
                List.of(new GraphRelationExists(relation("P576"),
                        GraphTraversalDirection.OUTGOING)),
                GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT));

        GraphEvidenceCondition copied = project.copy().rootClass().graphAdmissionCondition();

        assertEquals(project.rootClass().graphAdmissionCondition(), copied);
    }

    private static GraphPath path(String pid) {
        return GraphPath.direct(relation(pid), GraphTraversalDirection.OUTGOING);
    }

    private static GraphRelation relation(String pid) {
        return new GraphRelation("wikidata", pid);
    }
}
